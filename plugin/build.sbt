// SPDX-License-Identifier: Apache-2.0

// Chisel Debug Intrinsics Compiler Plugin
// Automatically instruments Chisel code with debug metadata

name := "chisel-debug-plugin"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  // Scala compiler API
  "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
  
  // Chisel (for type detection)
  "org.chipsalliance" %% "chisel" % "6.0.0" % "provided",
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "org.scalacheck" %% "scalacheck" % "1.17.0" % Test
)

// Compiler plugin packaging
Package.ManifestAttributes("Class-Path" -> "scala-compiler.jar")

// Generate plugin descriptor XML
ResourceGenerators.in(Compile) += Def.task {
  val file = (resourceManaged in Compile).value / "scalac-plugin.xml"
  IO.write(file,
    s"""<plugin>
       |  <name>chisel-debug</name>
       |  <classname>chisel3.debug.plugin.DebugIntrinsicsPlugin</classname>
       |</plugin>
       |""".stripMargin
  )
  Seq(file)
}.taskValue

// Export as JAR
assemblyOption in assembly := (assemblyOption in assembly).value.copy(
  includeScala = false,
  includeDependency = false
)
