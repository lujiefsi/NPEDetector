package com.lujie;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;

import com.ibm.wala.cfg.cdg.ControlDependenceGraph;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

public class ComplexControldependencyAnalysis extends ControldependencyAnalysis {

	public ComplexControldependencyAnalysis(CallGraph callGraph,
			Map<CGNode, CGNode> trasnCalleeToRootCallee) {
		super(callGraph, trasnCalleeToRootCallee);
	}

	@Override
	public Map<CGNode, Set<CGNode>> analysis(Map<CGNode, Set<CGNode>> calleeMap2Callers) {
		Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> ssaMayReferenceNull = findSSAMayReferenceNull(calleeMap2Callers);
		filterByIfNENull(ssaMayReferenceNull);
		return getNoCheckerCalleeToCallers(ssaMayReferenceNull);
	}

	private Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> findSSAMayReferenceNull(
			Map<CGNode, Set<CGNode>> calleeMapCallers) {
		Map<Pair<CGNode, CGNode>, Set<Pair<CGNode, SSAInstruction>>> result = HashMapFactory
				.make();
		for (Entry<CGNode, Set<CGNode>> entry : calleeMapCallers.entrySet()) {
			CGNode callee = entry.getKey();
			/* debug point for check special method */
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

	private boolean controlByNENull(CGNode node, SSAInstruction ssaInstruction) {
		/* debug point for check special method */
		if (node.toString().contains("FsDatasetImpl")
				&& node.toString().contains("getBlockLocalPathInfo")) {
			System.out.print("");
		}
		IR ir = node.getIR();
		PrunedCFG<SSAInstruction, ISSABasicBlock> exceptionPrunedCFG = ExitAndExceptionPrunedCFG
				.make(ir.getControlFlowGraph());
		ControlDependenceGraph<ISSABasicBlock> cdg = null;
		try {
			cdg = new ControlDependenceGraph<ISSABasicBlock>(exceptionPrunedCFG);
		} catch (Throwable e) {
			System.err.println("errors happends while contructing cdg of "
					+ Util.getSimpleMethodToString(node));
		}
		if (cdg == null) {
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
	
	private Map<CGNode, Set<CGNode>> getNoCheckerCalleeToCallers(
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
		return calleeMap2Callers;
	}
}
