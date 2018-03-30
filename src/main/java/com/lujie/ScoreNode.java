package com.lujie;

import java.util.Set;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.util.collections.Pair;

public class ScoreNode implements Comparable<ScoreNode> {
	public CGNode node = null;
	public Set<CGNode> callers = null;
	public int score = 0;

	public int compareTo(ScoreNode o) {
		if (o.score - this.score > 0){
			return 1;
		}
		if (o.score - this.score < 0 ){
			return -1;
		}
		return this.node.toString().compareTo(o.toString());
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((node == null) ? 0 : node.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ScoreNode other = (ScoreNode) obj;
		if (node == null) {
			if (other.node != null)
				return false;
		} else if (!node.equals(other.node))
			return false;
		return true;
	}
}
