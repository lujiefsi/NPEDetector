package com.lujie;

import com.ibm.wala.classLoader.IMethod;

public class ScoreCallee implements Comparable<ScoreCallee> {
	public IMethod method = null;
	public int score = 0;

	public ScoreCallee(IMethod method, int checkSize, int uncheckSize, int weight) {
		this.method = method;
		this.score = checkSize * weight - uncheckSize;
	}

	public int compareTo(ScoreCallee o) {
		if (o.score - this.score > 0) {
			return 1;
		}
		if (o.score - this.score < 0) {
			return -1;
		}
		return this.toString().compareTo(o.toString());
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((method == null) ? 0 : method.hashCode());
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
		ScoreCallee other = (ScoreCallee) obj;
		if (method == null) {
			if (other.method != null)
				return false;
		} else if (!method.equals(other.method))
			return false;
		return true;
	}
}
