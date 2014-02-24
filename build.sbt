organization := "com.sandinh"

name := "scala-memcached"

//git describe --abbrev=0

version := "2.10.5"

scalaVersion := "2.10.3"

scalacOptions ++= Seq(
  "-encoding", "UTF-8", "-deprecation", "-unchecked", "-feature", "-optimise",
  "-Yinline-warnings", "-Ywarn-dead-code", "-Yinline", "-Ydead-code"
)

javacOptions ++= Seq("-encoding", "UTF-8", "-source", "1.7", "-target", "1.7", "-Xlint:unchecked", "-Xlint:deprecation")

//unmanagedSourceDirectories in Test <+= baseDirectory {_ / "src" / "test" / "manual"}

parallelExecution in Test := false

libraryDependencies ++= Seq(
  "org.scalatest"           %% "scalatest"        % "2.0",
  "jmock"                    % "jmock"            % "1.2.0",
  "checkstyle"               % "checkstyle"       % "5.0",
  "com.google.code.findbugs" % "findbugs"         % "1.3.9",
  "com.novocode"             % "junit-interface"  % "0.10"
).map(_ % "test")

//http://youtrack.jetbrains.com/issue/SCL-6404
libraryDependencies ++= Seq(
  "log4j"                   % "log4j"               % "1.2.17",
  "org.slf4j"               % "slf4j-api"           % "1.7.5",
  "org.springframework"     % "spring-beans"        % "3.0.7.RELEASE",
  "com.codahale.metrics"    % "metrics-core"        % "3.0.1"
).map(_ % "optional")