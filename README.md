# NPEDetector
NPEDetector is designed to find the potential null pointer exception in the systems writen by java(especially for large distributed system).

# VS  NPEDetector -V1

The V1 version is in [master branch](https://github.com/lujiefsi/NPEDetector/tree/master) , but it is too slow to analyses the large  system who has millions line code.  Current version perform the analysis on class hierarchy(CHA, not the WALA built-in CHACallGraph), which can be very fast to analysis the large system(about 5 minutes for hadoop who has about 2,470,000 lines code). Based on CHA, NPEDetector may not be very precise due do lack pointer analysis, but we can include more methods that invoke by reflection. 
# Analysis the source code
Current version can only be used to analysis jar file. NPEDetector can also be implemented on [Abstract Syntax Tree(AST)](https://github.com/javaparser/javaparser), which can be used to analysis the source code(this is our futuer work).

## Found and fixed bugs(total 26)
   [CLOUDSTACK-10356](https://issues.apache.org/jira/browse/CLOUDSTACK-10356)(11)
   [ZK-3006](https://issues.apache.org/jira/browse/ZOOKEEPER-3006)(1)
   [ZK-3007](https://issues.apache.org/jira/browse/ZOOKEEPER-3007)(1)
   [HBASE-20419](https://issues.apache.org/jira/browse/HBASE-20419)(2)
   [YARN-8164](https://issues.apache.org/jira/browse/YARN-8164)(3)
   [YARN-7786](https://issues.apache.org/jira/browse/YARN-7786)(1)
   [STORM-3048](https://github.com/apache/storm/pull/2657)(3)
   [ZK-3009](https://issues.apache.org/jira/browse/ZOOKEEPER-3009)(1)
   [ZK-3009-3.4](https://issues.apache.org/jira/browse/ZOOKEEPER-3009)(3)

## Found and confirmed bugs
   [HELIX-701](https://github.com/apache/helix/pull/200)(2)
   [ZK-3008](https://issues.apache.org/jira/browse/ZOOKEEPER-3008)(1)
   [HBASE-20420](https://issues.apache.org/jira/browse/HBASE-20420)(8)
   [HDFS-13451](https://issues.apache.org/jira/browse/HDFS-13451)(7)
   [STORM-3049](https://github.com/apache/storm/pull/2656)(2)
   [STORM-3051](https://github.com/apache/storm/pull/2656)(3)
## Found and pending bugs
   [HDFS-13452](https://issues.apache.org/jira/browse/HDFS-13452)(2)
   [CASSANDRA-14385](https://issues.apache.org/jira/browse/CASSANDRA-14385)(3)
   [ZK-3009](https://issues.apache.org/jira/browse/ZOOKEEPER-3009)(2)
   [ZK-3010](https://issues.apache.org/jira/browse/ZOOKEEPER-3010)(2)
   [ZK-3011](https://issues.apache.org/jira/browse/ZOOKEEPER-3011)(4)
   [STORM-3050](https://github.com/apache/storm/pull/2655)(1)
## False postive   
   [HELIX-702](https://github.com/apache/helix/pull/201)(3)
   [HBASE-20420](https://issues.apache.org/jira/browse/HBASE-20420)(1)
   [CLOUDSTACK-10356](https://issues.apache.org/jira/browse/CLOUDSTACK-10356)(1)
## Some great suggestions
   [ZK-3009](https://issues.apache.org/jira/browse/ZOOKEEPER-3009)
   [HDFS-13451](https://issues.apache.org/jira/browse/HDFS-13451)
# Motivation
<div  align="center">    
 <img src="https://github.com/lujiefsi/NPEDetector/blob/master/hbase-13546.png" width="60%" height="60%" alt="hbase-13546" align=center />
</div>

above figure shows the bug in hbase:

1. HMaster crash.
2. Zookeeper expire the connection, so data related to master is null.
3. Client send http request for get region server status before HMaster retoot
4. After receive the request, RS will get master data from Zookeeper
5.  Due to step 2, RS get null, and reference it w/o check it.

We can see that this bug is complex(involed 4 node and one crash event).
Actually, the developers have considered the master crash situation while parse:

```java
//callee: parse
public Master parse(byte[] data){
    if (data == null){
        return null;
    }
}
```


but in its caller developer does not take the null pointer into account:
```java
//caller getMasterInfoPort
public getMasterInfoPort(byte[] data){
    Master master = parse(getData(false));
    return master.getInfoPort();
}
```

This bug shows that NPE happends in corner case but some (callee) developers are wake up this 
case. So we develop NPEDetector to catch this simple bug pattern:<font color=red size=4>callee return null, but caller
does not check it.</font>

NPEDetector will output two type result:

1. Nullable method who may return null method. Like "parse".
2. NPE point who may throw null pointer exception, like "getMasterInfoPort#4"

## Warning 
NPEDetector is designed for finding such simple NPEs, not systematically finding all NPEs. If you want the sound and precise NPE detector, you can visist anpther [NPEDetector](https://drona.csa.iisc.ac.in/~raghavan/software/NPE-VirtualBox/README.html)(same name with our tool). But I believe that it will take very long time to get the result for analysis hadoop. 



# Approach
NPEDetector is based on an famous static analysis framework [WALA](https://github.com/wala/WALA).

NPEDetector-V1 use the WALA built-in call graph that has pointer and other analysis, but is too slow to analysis the million lines level source code, like HADOOP.

In current version, we build call graph directly class hierarchy(CHA).

    step1 : iterate the all methods based on CHA.
    step2 : (1)record all return null methods(RNM).(2) record the relation between callee and caller.
    step3: check the return value of RNM is checked in caller. We adopt the  aggressive strategy to filter the NPE. so we have  many false negatives.
    step4: score the callee and rank, print the callee and its unchecked callers.
## Score

 score = unchecked size - checked size + exceptionWeight


unchecked size is the number of unchecked callers of RNM, checked size is the number of checked callers .

exception Weight is score when return null exists in exception handler.

# Some false negatives

```java
   ret = foo();
   if (ret != null) ret.foo1;
   ret.field;//NPE, but our tool won't not reporte it.
```



# Usage
1. Download the project
2. Using  "mvn clean compile assembly:single" to generate a runnable jar in target directory.
3. use command "java -jar ./target/NPEDectetor-1.0-SNAPSHOT-jar-with-dependencies.jar -inputDir /home/lujie/tmp -outputFileNA /tmp/Result_NA -outputFileNPE /tmp/Result_NPE" to analysis
4. inputDir  is the jar files that need to be analyzed, outputFileNA is the file who store the method who may return null, outputFileNPE  is the file  who store NPE point.
5. We use maven build our project, so you can use eclipse or other IDE import it as existed maven project. 

> 
