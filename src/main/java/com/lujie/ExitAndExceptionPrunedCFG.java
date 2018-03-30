package com.lujie;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.ipa.cfg.EdgeFilter;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;

public class ExitAndExceptionPrunedCFG {
	private static class ExitAndException<I, T extends IBasicBlock<I>>
			implements EdgeFilter<T> {
		private final ControlFlowGraph<I, T> cfg;

		public ExitAndException(ControlFlowGraph<I, T> cfg) {
			this.cfg = cfg;
		}

		public boolean hasNormalEdge(T src, T dst) {
			ISSABasicBlock srcBB = (ISSABasicBlock) src;
			if (!src.isEntryBlock()&&!src.isExitBlock()){
				SSAInstruction ssaInstruction = ((ISSABasicBlock) srcBB).getLastInstruction();
				if (Util.isExitInstruction(ssaInstruction)&&dst.isExitBlock()){
					return true;
				}
			}
			return cfg.getNormalSuccessors(src).contains(dst);
		}

		public boolean hasExceptionalEdge(T src, T dst) {
			return false;
		}
	}

	public static <I, T extends IBasicBlock<I>> PrunedCFG<I, T> make(ControlFlowGraph<I, T> cfg){
		return PrunedCFG.make(cfg, new ExitAndException(cfg));
	}
}
