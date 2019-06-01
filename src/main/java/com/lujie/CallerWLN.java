package com.lujie;

import com.ibm.wala.classLoader.IMethod;
/**
 * caller with line number
 */

public class CallerWLN {
	public IMethod method = null;
	public int linenumber = -1;
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + linenumber;
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
		CallerWLN other = (CallerWLN) obj;
		if (linenumber != other.linenumber)
			return false;
		if (method == null) {
			if (other.method != null)
				return false;
		} else if (!method.equals(other.method))
			return false;
		return true;
	}
}
