package com.lujie;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

public class SimpleControldependencyAnalysis extends ControldependencyAnalysis {

	public SimpleControldependencyAnalysis(CallGraph callGraph,
			Map<CGNode, CGNode> trasnCalleeToRootCallee) {
		super(callGraph, trasnCalleeToRootCallee);
	}

	@Override
	public Map<CGNode, Set<CGNode>> analysis(
			Map<CGNode, Set<CGNode>> calleeMap2Callers) {
		HashMap<CGNode, Set<CGNode>> ret = new HashMap<CGNode, Set<CGNode>>();
		for (Entry<CGNode, Set<CGNode>> entry : calleeMap2Callers.entrySet()) {
			if (entry.getKey().toString().contains("SecurityUtils")
					&& entry.getKey().toString().contains(
							"createSaslClient")) {
				System.out.print("");
			}
			for (CGNode caller : entry.getValue()) {
				if (!refernces(caller, entry.getKey())) {
					continue;
				}
				if (!controlByNEChecker(caller, entry.getKey())) {
					Set<CGNode> callers = ret.get(entry.getKey());
					if (callers == null) {
						callers = new HashSet<CGNode>();
						CGNode callee = trasnCalleeToRootCallee.get(entry
								.getKey());
						ret.put(callee, callers);
					}
					callers.add(caller);
				}
			}
		}
		return ret;
	}

	private boolean refernces(CGNode caller, CGNode callee) {
		Iterator<CallSiteReference> callSiteIterator = callGraph
				.getPossibleSites(caller, callee);
		while (callSiteIterator.hasNext()) {
			CallSiteReference callSiteReference = callSiteIterator.next();
			SSAAbstractInvokeInstruction[] ssaAbstractInvokeInstructions = caller
					.getIR().getCalls(callSiteReference);
			for (SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction : ssaAbstractInvokeInstructions) {
				int def = ssaAbstractInvokeInstruction.getDef();
				if (def == -1) {
					continue;
				}
				if (refernces(def, caller)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean controlByNEChecker(CGNode caller, CGNode callee) {
		Iterator<CallSiteReference> callSiteIterator = callGraph
				.getPossibleSites(caller, callee);
		while (callSiteIterator.hasNext()) {
			CallSiteReference callSiteReference = callSiteIterator.next();
			SSAAbstractInvokeInstruction[] ssaAbstractInvokeInstructions = caller
					.getIR().getCalls(callSiteReference);
			for (SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction : ssaAbstractInvokeInstructions) {
				int def = ssaAbstractInvokeInstruction.getDef();
				if (def == -1) {
					continue;
				}
				DefUse du = caller.getDU();
				Iterator<SSAInstruction> usesIterator = du.getUses(def);
				while (usesIterator.hasNext()) {
					SSAInstruction useInstruction = usesIterator.next();
					if (isNEChecker(caller.getIR(), useInstruction, def)) {
						Integer cnt = checkedCalleeCount.get(callee);
						if (cnt == null) {
							cnt = new Integer(0);
						}
						cnt++;
						checkedCalleeCount.put(callee, cnt);
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean refernces(int def, CGNode node) {
		// case 0:def.call()
		DefUse du = node.getDU();
		Iterator<SSAInstruction> usesIterator = du.getUses(def);
		while (usesIterator.hasNext()) {
			SSAInstruction useInstruction = usesIterator.next();
			if (useInstruction instanceof SSAAbstractInvokeInstruction) {
				if (!((SSAAbstractInvokeInstruction) useInstruction).isStatic()) {
					// case0 : ret = methodReturnNull();ret.foo();
					if (def == useInstruction.getUse(0)) {
						return true;
					} else {
						// TODO case1 : ret = methodReturnNull();this.foo(ret);
					}
				} else {
					// TODO case2 : ret = methodReturnNull();staticfoo(ret);
				}
			} else if (useInstruction instanceof SSAFieldAccessInstruction) {
				// case3 : ret = methodReturnNull();ret.a = 1;1==ret.a
				if (def == useInstruction.getUse(0)) {
					return true;
				} else {
					// TODO case3 : ret = methodReturnNull();this.a = ret;
				}
			} else if (useInstruction instanceof SSAArrayLengthInstruction) {
				// case3 : ret = methodReturnNull();ret.a = 1;1==ret.a
				if (def == useInstruction.getUse(0)) {
					return true;
				} else {
					// TODO case3 : ret = methodReturnNull();this.a = ret;
				}
			} else {
				// TODO: such as phi
			}
		}
		return false;
	}
}
