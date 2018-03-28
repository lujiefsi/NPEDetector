package com.lujie;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
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
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.util.io.FileUtil;

/**
 * 
 * */
public class NPEDectetor {
	private String jarDir = null;
	private CallGraph callGraph = null;
	// if project is too large, we should use
	// faster but not precise mainEntrypoints
	private boolean mainEntrypoints = false;
	private static long CUTOFF_SIZE = 10000000;
	private ClassHierarchy cha = null;

	public static void main(String[] args) throws IOException,
			ClassHierarchyException, IllegalArgumentException,
			CallGraphBuilderCancelException {
		NPEDectetor dectetor = new NPEDectetor();
		dectetor.checkJREVersion();
		dectetor.checkParameter(args);
		dectetor.makeCallGraph();
		Collection<CGNode> returnNullNodes = dectetor.findAllReturnNullNode();
		Map<CGNode, Set<CGNode>> nodesMayReferenceNull = dectetor
				.findNodesMayReferenceNull(returnNullNodes);
	}

	private Map<CGNode, Set<CGNode>> findNodesMayReferenceNull(
			Collection<CGNode> returnNullNodes) {
		Map<CGNode, Set<CGNode>> calleeMapCallers = HashMapFactory.make();
		for (CGNode returnNullNode : returnNullNodes) {
			Collection<Pair<CGNode, CGNode>> callerAndCallees = this
					.findCaller(returnNullNode, new HashSet<CGNode>());
			for (Pair<CGNode, CGNode> pair : callerAndCallees) {
				if (calleeMapCallers.get(pair.snd) == null) {
					calleeMapCallers.put(pair.snd, new HashSet<CGNode>());
				}
				calleeMapCallers.get(pair.snd).add(pair.snd);
			}
		}
		// we assume if a callee have more than 5 caller
		// developer may bother to add !null
		Iterator<Entry<CGNode, Set<CGNode>>> iterator = calleeMapCallers
				.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<CGNode, Set<CGNode>> entry = iterator.next();
			if (entry.getValue().size() >= 5) {
				iterator.remove();
			}
		}
		return calleeMapCallers;
	}

	private void checkParameter(String[] args) {
		if (args.length != 2) {
			System.out.println("wrong parameter number");
			System.exit(1);
		}
		jarDir = args[0];
		if (!(new File(jarDir).isDirectory())) {
			System.out.println("wrong tart jar directory");
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
		if (mainEntrypoints) {
			entryPointIterator = com.ibm.wala.ipa.callgraph.impl.Util
					.makeMainEntrypoints(scope, cha);
		} else {
			entryPointIterator = new AllApplicationEntrypoints(scope, cha);
		}
		AnalysisOptions options = new AnalysisOptions(scope, entryPointIterator);
		// 0-CFA is faster and more precise
		CallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util
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
		if (size > CUTOFF_SIZE) {
			mainEntrypoints = true;
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

	private void checkJREVersion() {
		try {
			Properties p = WalaProperties.loadProperties();
			String javaHome = p.getProperty(WalaProperties.J2SE_DIR);
			if (!javaHome.contains("1.7")) {
				System.err
						.println("check your javahome wrong jdk version , must be 1.7");
			}
		} catch (WalaException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private Collection<CGNode> findAllReturnNullNode() {
		Set<CGNode> returnNullNodes = HashSetFactory.make();
		for (CGNode node : callGraph) {
			if (Util.isApplicationNode(node) && isReturnNullNode(node)) {
				System.out.println(node);
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
				if (use != -1 && symbolTable.isNullConstant(use)) {
					return true;
				}
			}
		}
		// case n:?, maybe
		return false;
	}

	private Collection<Pair<CGNode, CGNode>> findCaller(CGNode callee,
			Collection<CGNode> checkedNode) {
		Set<Pair<CGNode, CGNode>> ret = HashSetFactory.make();
		if (checkedNode.contains(callee))
			return ret;
		checkedNode.add(callee);
		Iterator<CGNode> callerIterator = callGraph.getPredNodes(callee);
		while (callerIterator.hasNext()) {
			CGNode caller = callerIterator.next();
			if (caller.equals(callGraph.getFakeRootNode())
					|| !Util.isApplicationNode(caller)) {
				continue;
			}
			IR ir = caller.getIR();
			DefUse du = caller.getDU();
			Iterator<CallSiteReference> callSiteIterator = callGraph
					.getPossibleSites(caller, callee);
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
							ret.add(Pair.make(caller, callee));
							continue;
						}
						findCaller(caller, checkedNode);
					}
				}
			}
		}
		return ret;
	}
}
