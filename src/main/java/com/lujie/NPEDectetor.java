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
public class NPEDectetor {
	private String jarDir = null;
	private CallGraph callGraph = null;
	// if project is too large, we should use
	// faster but not precise mainEntrypoints
	private boolean mainEntrypoints = false;
	private static long CUTOFF_SIZE = 100000000;
	private ClassHierarchy cha = null;
	private Map<CGNode, Integer> checkedCalleeCount;
	private Map<CGNode, CGNode> trasnCalleeToRootCallee;

	public NPEDectetor() {
		this.checkedCalleeCount = HashMapFactory.make();
		trasnCalleeToRootCallee = HashMapFactory.make();
	}

	public static void main(String[] args) throws IOException,
			ClassHierarchyException, IllegalArgumentException,
			CallGraphBuilderCancelException {
		NPEDectetor dectetor = new NPEDectetor();
		dectetor.checkJREVersion();
		dectetor.checkParameter(args);
		dectetor.makeCallGraph();
		System.out.println("start to find potential NPE");
		Collection<CGNode> returnNullNodes = dectetor.findAllReturnNullNode();
		Map<CGNode, Set<CGNode>> calleeMap2Callers = dectetor
				.findCallers(returnNullNodes);
		Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> ssaMayReferenceNull = dectetor
				.findSSAMayReferenceNull(calleeMap2Callers);
		dectetor.filterByIfNENull(ssaMayReferenceNull);
		Set<ScoreNode> scoreNodes = dectetor.buildScoreSet(ssaMayReferenceNull);
		dectetor.dumpResult(args[1], scoreNodes);
	}

	private void dumpResult(String fileName, Set<ScoreNode> scoreNodes) {
		File file = new File(fileName);
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
			Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> map) {
		Iterator<Entry<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>>> entryIterator = map
				.entrySet().iterator();
		Set<ScoreNode> ret = new TreeSet<ScoreNode>();
		Map<CGNode, Set<CGNode>> calleeMap2Callers = HashMapFactory.make();
		while (entryIterator.hasNext()) {
			Entry<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> entry = entryIterator
					.next();
			CGNode caller = entry.getKey().fst;
			CGNode callee = trasnCalleeToRootCallee.get(entry.getKey().snd);
			Set<CGNode> callers = calleeMap2Callers.get(callee);
			if (callers == null) {
				callers = HashSetFactory.make();
				calleeMap2Callers.put(callee, callers);
			}
			callers.add(caller);
		}
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
		}else{
			message.append("AllApplciationEntryPoint");
		}
		System.out.println(message);
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
				Util.exitWithErrorMessage("check your javahome wrong jdk version , must be 1.7");
			}
		} catch (WalaException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private Collection<CGNode> findAllReturnNullNode() {
		Set<CGNode> returnNullNodes = HashSetFactory.make();
		for (CGNode node : callGraph) {
			/*debug point for check special method*/
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
			/*debug point for check special method*/
			if (returnNullNode.toString().contains("BinaryInputArchive")
					&& returnNullNode.toString().contains("readBuffer")) {
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

	private Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> findSSAMayReferenceNull(
			Map<CGNode, Set<CGNode>> calleeMapCallers) {
		Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> result = HashMapFactory
				.make();
		for (Entry<CGNode, Set<CGNode>> entry : calleeMapCallers.entrySet()) {
			CGNode callee = entry.getKey();
			/*debug point for check special method*/
			if (callee.toString().contains("BinaryInputArchive")
					&& callee.toString().contains("readBuffer")) {
				System.out.print("");
			}
			Set<CGNode> callers = entry.getValue();
			for (Iterator<CGNode> callerIterator = callers.iterator(); callerIterator
					.hasNext();) {
				CGNode caller = callerIterator.next();
				IR ir = caller.getIR();
				Iterator<CallSiteReference> callSiteIterator = callGraph
						.getPossibleSites(caller, callee);
				Set<Pair<CGNode, SSAInstruction>> refernces = HashSetFactory
						.make();
				while (callSiteIterator.hasNext()) {
					CallSiteReference callSiteReference = callSiteIterator
							.next();
					SSAAbstractInvokeInstruction[] ssaAbstractInvokeInstructions = ir
							.getCalls(callSiteReference);
					for (SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction : ssaAbstractInvokeInstructions) {
						int def = ssaAbstractInvokeInstruction.getDef();
						if (def == -1) {
							continue;
						}
						refernces.addAll(getFinalRefernces(def, caller));
					}
				}
				if (refernces.size() == 0) {
					callerIterator.remove();
				} else {
					result.put(Pair.make(caller, callee), refernces);
				}
			}
		}
		Iterator<Entry<CGNode, Set<CGNode>>> entryIterator = calleeMapCallers
				.entrySet().iterator();
		while (entryIterator.hasNext()) {
			Entry<CGNode, Set<CGNode>> entry = entryIterator.next();
			if (entry.getValue().size() == 0) {
				entryIterator.remove();
			}
		}
		return result;
	}

	private Set<Pair<CGNode, SSAInstruction>> getFinalRefernces(int def,
			CGNode node) {
		Set<Pair<CGNode, SSAInstruction>> refernces = HashSetFactory.make();
		// case 0:def.call()
		DefUse du = node.getDU();
		Iterator<SSAInstruction> usesIterator = du.getUses(def);
		while (usesIterator.hasNext()) {
			SSAInstruction useInstruction = usesIterator.next();
			if (useInstruction instanceof SSAAbstractInvokeInstruction) {
				if (!((SSAAbstractInvokeInstruction) useInstruction).isStatic()) {
					// case0 : ret = methodReturnNull();ret.foo();
					if (def == useInstruction.getUse(0)) {
						refernces.add(Pair.make(node, useInstruction));
					} else {
						// TODO case1 : ret = methodReturnNull();this.foo(ret);
					}
				} else {
					// TODO case2 : ret = methodReturnNull();staticfoo(ret);
				}
			} else if (useInstruction instanceof SSAFieldAccessInstruction) {
				// case3 : ret = methodReturnNull();ret.a = 1;1==ret.a
				if (def == useInstruction.getUse(0)) {
					refernces.add(Pair.make(node, useInstruction));
				} else {
					// TODO case3 : ret = methodReturnNull();this.a = ret;
				}
			} else if (useInstruction instanceof SSAArrayLengthInstruction) {
				// case3 : ret = methodReturnNull();ret.a = 1;1==ret.a
				if (def == useInstruction.getUse(0)) {
					refernces.add(Pair.make(node, useInstruction));
				} else {
					// TODO case3 : ret = methodReturnNull();this.a = ret;
				}
			} else {
				// TODO: such as phi
			}
		}
		return refernces;
	}

	private void filterByIfNENull(
			Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> map) {
		Iterator<Entry<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>>> entryIterator = map
				.entrySet().iterator();
		while (entryIterator.hasNext()) {
			Entry<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> entry = entryIterator
					.next();
			Iterator<Pair<CGNode, SSAInstruction>> pairIterator = entry
					.getValue().iterator();
			while (pairIterator.hasNext()) {
				Pair<CGNode, SSAInstruction> pair = pairIterator.next();
				if (controlByNENull(pair.fst, pair.snd)) {
					pairIterator.remove();
					Integer cnt = checkedCalleeCount.get(entry.getKey().snd);
					if (cnt == null) {
						cnt = new Integer(0);
					}
					cnt++;
					checkedCalleeCount.put(entry.getKey().snd, cnt);
				}
			}
			if (entry.getValue().size() == 0) {
				entryIterator.remove();
			}
		}
	}

	private boolean controlByNENull(CGNode node, SSAInstruction ssaInstruction) {
		/*debug point for check special method*/
		if (node.toString().contains("CassandraStreamReader")
				&& node.toString().contains("read")) {
			System.out.print("");
		}
		IR ir = node.getIR();
		PrunedCFG<SSAInstruction, ISSABasicBlock> exceptionPrunedCFG = ExitAndExceptionPrunedCFG
				.make(ir.getControlFlowGraph());
		ControlDependenceGraph<ISSABasicBlock> cdg = null;
		try{
			cdg = new ControlDependenceGraph<ISSABasicBlock>(exceptionPrunedCFG);
		}catch(Throwable e){
			System.err.println("errors happends while contructing cdg of " + 
										Util.getSimpleMethodToString(node));
		}
		if (cdg == null){
			return true;
		}
		ISSABasicBlock bb = ir.getBasicBlockForInstruction(ssaInstruction);
		Set<ISSABasicBlock> preBBs = HashSetFactory.make();
		findPreBB(cdg, preBBs, bb);
		for (ISSABasicBlock preBB : preBBs) {
			for (SSAInstruction controlSSAInstruction : preBB) {
				if (controlSSAInstruction instanceof SSAConditionalBranchInstruction) {
					if (controlSSAInstruction.getNumberOfUses() != 2) {
						continue;
					}
					int use0 = controlSSAInstruction.getUse(0);
					int use1 = controlSSAInstruction.getUse(1);
					if (ir.getSymbolTable().isNullConstant(use0)
							&& use1 == ssaInstruction.getUse(0)) {
						return true;
					}
					if (ir.getSymbolTable().isNullConstant(use1)
							&& use0 == ssaInstruction.getUse(0)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void findPreBB(ControlDependenceGraph<ISSABasicBlock> cdg,
			Set<ISSABasicBlock> preBBs, ISSABasicBlock bb) {
		Iterator<ISSABasicBlock> preBBIterator = cdg.getPredNodes(bb);
		while (preBBIterator.hasNext()) {
			ISSABasicBlock preBB = preBBIterator.next();
			if (preBBs.contains(preBB))
				continue;
			preBBs.add(preBB);
			findPreBB(cdg, preBBs, preBB);
		}
	}
}
