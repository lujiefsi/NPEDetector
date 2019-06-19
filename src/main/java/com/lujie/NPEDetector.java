package com.lujie;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.TreeSet;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.util.Set;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.util.io.FileUtil;

/**
 * 
 * */
public class NPEDetector {
	private String inputDir = null;
	private NPECallGraph callGraph = null;
	AnalysisCache analysisCache = new AnalysisCacheImpl();
	private String outputFileNA = null;
	private String outputFileNPE = null;
	private ClassHierarchy cha;

	public static void main(String[] args) throws IOException, ClassHierarchyException, IllegalArgumentException,
			CallGraphBuilderCancelException, ParseException {
		NPEDetector dectetor = new NPEDetector();
		dectetor.checkParameter(args);
		dectetor.makeCallGraph();
		dectetor.dumpResult(dectetor.buildScoreCallees());
	}

	private void dumpResult(Set<ScoreCallee> scoreNodes) {
		File fileNullable = new File(outputFileNA);
		FileWriter writerNullable = null;
		File fileNPE = new File(outputFileNPE);
		FileWriter writerNPE = null;
		try {
			writerNullable = new FileWriter(fileNullable);
			writerNPE = new FileWriter(fileNPE);
			for (ScoreCallee scoreNode : scoreNodes) {
				writerNullable.write(scoreNode.method + "\n");
				if (callGraph.getUncheckCallers(scoreNode.method).isEmpty()) {
					continue;
				}
				StringBuilder sb = new StringBuilder();
				sb.append(scoreNode.method);
				for (CallerWLN caller : callGraph.getUncheckCallers(scoreNode.method)) {
					sb.append(" ");
					sb.append(Util.getSimpleMethodToString(caller.method) + "#" + caller.linenumber);
				}
				sb.append("\n");
				writerNPE.write(sb.toString());
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (writerNullable != null) {
				try {
					writerNullable.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (writerNPE != null) {
				try {
					writerNPE.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	private Set<ScoreCallee> buildScoreCallees() {
		Set<ScoreCallee> ret = new TreeSet<ScoreCallee>();
		for (IMethod method : callGraph.getReturnNullMethods()) {
			ScoreCallee scoreNode = new ScoreCallee(method, callGraph.getCheckSize(method),
					callGraph.getUncheckSize(method), callGraph.isExceptionMethod(method));
			ret.add(scoreNode);
		}
		return ret;
	}

	private void checkParameter(String[] args) throws ParseException {
		Options options = new Options();
		options.addOption("inputDir", true, "the directory including the jars that are test ");
		options.addOption("outputFileNA", true, "the file where the result output");
		options.addOption("outputFileNPE", true, "the file where the result output");
		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = parser.parse(options, args);
		if (cmd.hasOption("inputDir")) {
			inputDir = cmd.getOptionValue("inputDir");
		}
		if (inputDir == null) {
			Util.exitWithErrorMessage("you should specify input directory");
		}
		if (cmd.hasOption("outputFileNA")) {
			outputFileNA = cmd.getOptionValue("outputFileNA");
		}
		if (outputFileNA == null) {
			Util.exitWithErrorMessage("you should specify output file for nullable method");
		}
		if (cmd.hasOption("outputFileNPE")) {
			outputFileNPE = cmd.getOptionValue("outputFileNPE");
		}
		if (outputFileNPE == null) {
			Util.exitWithErrorMessage("you should specify output file for potential Null Pointer Exception");
		}
	}

	private void makeCallGraph()
			throws IOException, ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException {
		long start_time = System.currentTimeMillis();
		System.out.println("start to build call graph");
		FileProvider fileProvider = new FileProvider();
		File exclusionsFile = fileProvider.getFile("Java60RegressionExclusions.txt");
		String jarFiles = findJarFiles(new String[] { inputDir });
		AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(jarFiles, exclusionsFile);
		cha = ClassHierarchyFactory.make(scope);
		callGraph = new NPECallGraph(cha, true);
		callGraph.init();
		System.out.println("Time spent ont building call graph " + (System.currentTimeMillis() - start_time) + "ms");
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
}
