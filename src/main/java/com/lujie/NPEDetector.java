package com.lujie;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import com.ibm.wala.cfg.cdg.ControlDependenceGraph;
import com.ibm.wala.cfg.exc.intra.SSACFGNullPointerAnalysis;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.CallGraphStats;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.shrikeBT.ArrayLengthInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.util.io.FileUtil;
import com.sun.xml.internal.bind.v2.TODO;

/**
 * 
 * */
public class NPEDetector {
	private String jarDir = null;
	private CallGraph callGraph = null;
	// if project is too large, we should use
	// faster but not precise mainEntrypoints
	private boolean mainEntrypoints = false;
	private boolean debug = false;
	private static long CUTOFF_SIZE = 100000000;
	private boolean simpleAnalysis = true;
	private String outputFile = null;
	private ClassHierarchy cha = null;
	private Map<CGNode, CGNode> trasnCalleeToRootCallee;

	public NPEDetector() {
		trasnCalleeToRootCallee = HashMapFactory.make();
	}

	public static void main(String[] args) throws IOException,
			ClassHierarchyException, IllegalArgumentException,
			CallGraphBuilderCancelException {
		NPEDetector dectetor = new NPEDetector();
		dectetor.checkParameter();
		dectetor.makeCallGraph();
		System.out.println("start to find potential NPE");
		Collection<CGNode> returnNullNodes = dectetor.findAllReturnNullNode();
		Map<CGNode, Set<CGNode>> calleeMap2Callers = dectetor
				.findCallers(returnNullNodes);
		ControldependencyAnalysis controldependencyAnalysis = dectetor
				.getControlDependencyAnalysis();

		Map<CGNode, Set<CGNode>> noNEChekerCalleeMap2Callers = controldependencyAnalysis
				.analysis(calleeMap2Callers);
		Set<ScoreNode> scoreNodes = dectetor.buildScoreSet(
				noNEChekerCalleeMap2Callers,
				controldependencyAnalysis.getCheckedCalleeCount());
		dectetor.dumpResult(scoreNodes);
	}

	private ControldependencyAnalysis getControlDependencyAnalysis() {
		if (simpleAnalysis) {
			return new SimpleControldependencyAnalysis(callGraph,
					trasnCalleeToRootCallee);
		} else {
			return new ComplexControldependencyAnalysis(callGraph,
					trasnCalleeToRootCallee);
		}
	}

	private void dumpResult(Set<ScoreNode> scoreNodes) {
		File file = new File(outputFile);
		FileWriter writer = null;
		try {
			writer = new FileWriter(file);
			for (ScoreNode scoreNode : scoreNodes) {
				StringBuilder sb = new StringBuilder();
				sb.append(Util.getSimpleMethodToString(scoreNode.node));
				for (CGNode caller : scoreNode.callers) {
					sb.append(" ");
					sb.append(Util.getSimpleMethodToString(caller));
				}
				sb.append("\n");
				writer.write(sb.toString());
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	private Set<ScoreNode> buildScoreSet(
			Map<CGNode, Set<CGNode>> calleeMap2Callers,
			Map<CGNode, Integer> checkedCalleeCount) {
		Set<ScoreNode> ret = new TreeSet<ScoreNode>();
		for (Entry<CGNode, Set<CGNode>> entry : calleeMap2Callers.entrySet()) {
			ScoreNode scoreNode = new ScoreNode();
			scoreNode.node = entry.getKey();
			scoreNode.callers = entry.getValue();
			scoreNode.score -= entry.getValue().size();
			Integer score = checkedCalleeCount.get(entry.getKey());
			if (score != null) {
				scoreNode.score += score * 10;
			}
			ret.add(scoreNode);
		}
		return ret;
	}

	private void checkParameter() {
		try {

			// check jre
			Properties p = WalaProperties.loadProperties();
			String javaHome = p.getProperty(WalaProperties.J2SE_DIR);
			if (!javaHome.contains("1.7")) {
				Util.exitWithErrorMessage("check your javahome wrong jdk version , must be 1.7");
			}
			// check jardir
			jarDir = p.getProperty("jardir");
			if (jarDir == null) {
				Util.exitWithErrorMessage("please configure your jardir");
			}
			// user may mistake leave a blank space end of the path
			jarDir.replaceAll(" ", "");
			
			outputFile = p.getProperty("outputfile");
			if (outputFile == null) {
				Util.exitWithErrorMessage("please configure your outputfile");
			}
			outputFile.replaceAll(" ", "");
			// check debug
			String debugString = p.getProperty("debug");
			if (debugString != null && debugString.equals("true")) {
				debug = true;
			}
			// check cda
			String cda = p.getProperty("cda");
			if (cda != null && cda.equals("complex")) {
				simpleAnalysis = false;
			}
		} catch (WalaException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private void makeCallGraph() throws IOException, ClassHierarchyException,
			IllegalArgumentException, CallGraphBuilderCancelException {
		long start_time = System.currentTimeMillis();
		System.out.println("start to make call graph");
		FileProvider fileProvider = new FileProvider();
		File exclusionsFile = fileProvider
				.getFile("Java60RegressionExclusions.txt");
		String jarFiles = findJarFiles(new String[] { jarDir });
		AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(
				jarFiles, exclusionsFile);
		cha = ClassHierarchyFactory.make(scope);
		Iterable<Entrypoint> entryPointIterator = null;
		if (debug) {
			entryPointIterator = addSpecialEntryPoint();
		} else if (mainEntrypoints) {
			entryPointIterator = com.ibm.wala.ipa.callgraph.impl.Util
					.makeMainEntrypoints(scope, cha);
		} else {
			entryPointIterator = new AllApplicationEntrypoints(scope, cha);
		}
		AnalysisOptions options = new AnalysisOptions(scope, entryPointIterator);
		// 0-CFA is faster and more precise
		CallGraphBuilder<?> builder = com.ibm.wala.ipa.callgraph.impl.Util
				.makeZeroCFABuilder(options, new AnalysisCacheImpl(), cha,
						scope);
		callGraph = builder.makeCallGraph(options, null);
		System.out.println(CallGraphStats.getStats(callGraph));
		System.out.println("Time spent ont building CHA and CG:"
				+ (System.currentTimeMillis() - start_time) + "ms");
	}

	private String findJarFiles(String[] directories) {
		long size = 0;
		Collection<String> result = HashSetFactory.make();
		for (int i = 0; i < directories.length; i++) {
			for (File f : FileUtil.listFiles(directories[i], ".*\\.jar", true)) {
				result.add(f.getAbsolutePath());
				size += f.length();
			}
		}
		StringBuilder message = new StringBuilder();
		message.append("project size is ");
		message.append(size);
		message.append(" so use ");
		if (size > CUTOFF_SIZE) {
			message.append("MainApplciationEntryPoint");
			mainEntrypoints = true;
		} else {
			message.append("AllApplciationEntryPoint");
		}
		if (!debug) {
			System.out.println(message);
		}
		return composeString(result);
	}

	private String composeString(Collection<String> s) {
		StringBuffer result = new StringBuffer();
		Iterator<String> it = s.iterator();
		for (int i = 0; i < s.size() - 1; i++) {
			result.append(it.next());
			result.append(File.pathSeparator);
		}
		result.append(it.next());
		return result.toString();
	}

	private Collection<CGNode> findAllReturnNullNode() {
		Set<CGNode> returnNullNodes = HashSetFactory.make();
		for (CGNode node : callGraph) {
			/* debug point for check special method */
			if (node.toString().contains("BinaryInputArchive")
					&& node.toString().contains("readBuffer")) {
				System.out.print("");
			}
			if (Util.isApplicationNode(node) && isReturnNullNode(node)) {
				returnNullNodes.add(node);
			}
		}
		return returnNullNodes;
	}

	private boolean isReturnNullNode(CGNode node) {
		IR ir = node.getIR();
		if (ir == null)
			return false;
		for (SSAInstruction ins : ir.getInstructions()) {
			if (ins instanceof SSAReturnInstruction
					&& isReturnNullInstruction((SSAReturnInstruction) ins, node)) {
				return true;
			}
		}
		return false;
	}

	private boolean isReturnNullInstruction(SSAReturnInstruction returnIns,
			CGNode node) {
		IR ir = node.getIR();
		DefUse defUse = node.getDU();
		SymbolTable symbolTable = ir.getSymbolTable();
		int use = returnIns.getUse(0);
		if (use < 0) {
			return false;
		}
		// case 0: directly "return null"
		if (symbolTable.isNullConstant(use)) {
			return true;
		}
		// case 1: phi v0 = v1:#str, v2:#null,...
		// TODO handle that v1 is still def by SSAPhiInstruction
		SSAInstruction defIns = defUse.getDef(use);
		if (defIns instanceof SSAPhiInstruction) {
			for (int i = 0; i < defIns.getNumberOfUses(); i++) {
				use = defIns.getUse(0);
				if (use != -1 && symbolTable.isNullConstant(use)) {
					return true;
				}
			}
		}
		// case n:?, maybe
		return false;
	}

	private Collection<Pair<CGNode, CGNode>> findCaller(CGNode rootCallee,
			CGNode transcallee, Collection<CGNode> checkedNode) {
		Set<Pair<CGNode, CGNode>> ret = HashSetFactory.make();
		if (checkedNode.contains(transcallee))
			return ret;
		checkedNode.add(transcallee);
		Iterator<CGNode> callerIterator = callGraph.getPredNodes(transcallee);
		while (callerIterator.hasNext()) {
			CGNode caller = callerIterator.next();
			if (caller.equals(callGraph.getFakeRootNode())
					|| !Util.isApplicationNode(caller)) {
				continue;
			}
			IR ir = caller.getIR();
			DefUse du = caller.getDU();
			Iterator<CallSiteReference> callSiteIterator = callGraph
					.getPossibleSites(caller, transcallee);
			while (callSiteIterator.hasNext()) {
				CallSiteReference callSite = callSiteIterator.next();
				SSAAbstractInvokeInstruction[] ssaAbstractInvokeInstructions = ir
						.getCalls(callSite);
				for (SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction : ssaAbstractInvokeInstructions) {
					int def = ssaAbstractInvokeInstruction.getDef();
					Iterator<SSAInstruction> useIterator = du.getUses(def);
					if (useIterator.hasNext()) {
						SSAInstruction useInstruction = useIterator.next();
						if (!(useInstruction instanceof SSAReturnInstruction)) {
							ret.add(Pair.make(caller, transcallee));
							trasnCalleeToRootCallee
									.put(transcallee, rootCallee);
							continue;
						}
						ret.addAll(findCaller(rootCallee, caller, checkedNode));
					}
				}
			}
		}
		return ret;
	}

	private Map<CGNode, Set<CGNode>> findCallers(
			Collection<CGNode> returnNullNodes) {
		Map<CGNode, Set<CGNode>> calleeMapCallers = HashMapFactory.make();
		for (CGNode returnNullNode : returnNullNodes) {
			/* debug point for check special method */
			if (returnNullNode.toString().contains("SecurityUtils")
					&& returnNullNode.toString().contains("createSaslClient")) {
				System.out.print("");
			}
			Collection<Pair<CGNode, CGNode>> callerAndCallees = this
					.findCaller(returnNullNode, returnNullNode,
							new HashSet<CGNode>());
			for (Pair<CGNode, CGNode> pair : callerAndCallees) {
				if (calleeMapCallers.get(pair.snd) == null) {
					calleeMapCallers.put(pair.snd, new HashSet<CGNode>());
				}
				calleeMapCallers.get(pair.snd).add(pair.fst);
			}
		}
		return calleeMapCallers;
	}

	private Iterable<Entrypoint> addSpecialEntryPoint() {
		final Set<Entrypoint> entrypoints = new HashSet<Entrypoint>();
		for (IClass cls : cha) {
			if (cls.getName().toString().endsWith("FsDatasetImpl")) {
				for (IMethod method : cls.getAllMethods()) {
					entrypoints.add(new DefaultEntrypoint(method, cha));
				}
			}
		}
		return new Iterable<Entrypoint>() {

			public Iterator<Entrypoint> iterator() {
				return entrypoints.iterator();
			}
		};
	}
}
