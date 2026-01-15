// SPDX-License-Identifier: Apache-2.0

name := "chisel-debug-plugin"

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-compiler" % scalaVersion.value,
  "org.scala-lang" % "scala-reflect" % scalaVersion.value
)

// Chisel is provided (will be on classpath when plugin is used)
libraryDependencies += "org.chipsalliance" %% "chisel" % "6.0.0" % "provided"

// Package as compiler plugin
exportJars := true

// Add scalac-plugin.xml to resources (already in src/main/resources)
