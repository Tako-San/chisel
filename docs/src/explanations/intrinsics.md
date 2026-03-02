---
layout: docs
title:  "Intrinsics"
section: "chisel3"
---

# Intrinsics

Chisel *Intrinsics* are used to express implementation defined functionality. 
Intrinsics provide a way for specific compilers to extend the capabilities of
the language in ways which are not implementable with library code.

Intrinsics will be typechecked by the implementation.  What intrinsics are 
available is documented by an implementation.

The `Intrinsic` and `IntrinsicExpr` can be used to create intrinsic statements
and expressions.

### Parameterization

Parameters can be passed as an argument to the IntModule constructor.

### Intrinsic Expression Example

This following creates an intrinsic for the intrinsic named "MyIntrinsic".
It takes a parameter named "STRING" and has several inputs.

```scala mdoc:invisible
import chisel3._
// Below is required for scala 3 migration
import chisel3.experimental.fromStringToStringParam
```

```scala mdoc:compile-only
class Foo extends RawModule {
  val myresult = IntrinsicExpr("MyIntrinsic", UInt(32.W), "STRING" -> "test")(3.U, 5.U)
}
```

## Debug Intrinsics

Chisel supports debug intrinsics for hardware debugging tools. These emit
debug metadata as JSON payloads attached to the generated FIRRTL.

### Enabling Debug Intrinsics

**Note**: Debug metadata is currently only supported for Scala 2 projects. Scala 3 support is planned for future releases.

Debug metadata emission requires **two separate activations** to function correctly:

1. **Compile-time activation**: Enable the Chisel compiler plugin to collect debug metadata. The plugin uses the public wrapper `chisel3.debug.DebugMeta.record` to insert metadata calls from any package while respecting visibility rules.
2. **Elaboration-time activation**: Enable the FIRRTL emitter to output the collected metadata as debug intrinsics using the `--emit-debug-type-info` flag.

Without both enabled:
- **Missing compile-time activation**: No metadata is collected (plugin doesn't insert calls via `chisel3.debug.DebugMeta.record`)
- **Missing elaboration-time activation**: Emitter outputs "unknown" values for all fields

#### Compile-time Activation

Enable the compiler plugin by adding the following option to your build configuration:

**For sbt:**
```scala
scalacOptions ++= Seq(
  "-P:chiselplugin:emitDebugTypeInfo"
)
```

**For Mill:**
```scala
def scalacOptions = T {
  super.scalacOptions() ++ Seq(
    "-P:chiselplugin:emitDebugTypeInfo"
  )
}
```

**For direct scalac:**
```bash
scalac -P:chiselplugin:emitDebugTypeInfo MyModule.scala
```

#### Elaboration-time Activation

Use the `--emit-debug-type-info` flag to enable debug metadata emission in the stage:

Via command line:
```bash
circt chisel --emit-debug-type-info MyModule.scala
```

Or programmatically using `ChiselStage`:
```scala
import circt.stage.ChiselStage

val chirrtl = ChiselStage.emitCHIRRTL(
  new MyModule,
  args = Array("--emit-debug-type-info")
)
```

#### Complete Example

Here's how to properly configure both options in a typical sbt project:

**build.sbt:**
```scala
// Enable the compiler plugin at compile-time
Compile / scalacOptions ++= Seq(
  "-P:chiselplugin:emitDebugTypeInfo"
)

// Add the Chisel compiler plugin dependency
libraryDependencies += "org.chipsalliance" %% "chisel-plugin" % "6.0.0" % "compile->compile"
```

**Main.scala:**
```scala
import chisel3._
import circt.stage.ChiselStage

object Main extends App {
  // Both compile-time plugin and elaboration-time flag must be enabled
  val chirrtl = ChiselStage.emitCHIRRTL(
    new MyModule,
    args = Array("--emit-debug-type-info")
  )
  
  println(chirrtl)
}

class MyModule extends Module {
  val in = IO(Input(UInt(8.W)))
  val out = IO(Output(UInt(8.W)))
  out := in
}
```

### `circt_debug_typetag`

Emits type information for signals (ports, wires, registers, etc.).

**JSON Schema:**

```json
{
  "className":  String,   // required: Chisel type name (Bool, UInt, MyBundle, etc.)
  "width":      String,   // required: bit width or "inferred"
  "binding":    String,   // required: "port" | "reg" | "wire" | "memport" | "unknown"
  "direction":  String,   // required: "input" | "output" | "flip" | "unspecified"
  "sourceLoc":  String,   // required: "file.scala:line" or "unknown"
  "params":     String,   // optional: human-readable constructor params summary
  "fields": {             // optional: for Bundle types
    "<fieldName>": {
      "typeName":  String,   // required: Scala class name of the field type
      "width":     String,   // required
      "direction": String,   // required
      // ... nested "fields"/"vecLength"/"element" recursively
    }
  },
  "vecLength": Number,       // optional: for Vec
  "element": { ... },        // optional: for Vec element type
  "enumDef": {               // optional: for ChiselEnum
    "name":     String,      // required: enum type name
    "variants": [            // required: array of variant objects
      {
        "name":  String,     // variant name
        "value": Number      // numeric value
      }
    ]
  }
}
```

**Example JSON output for a module with IO:**

```scala
class MyModule extends Module {
  val in = IO(Input(UInt(8.W)))
  val out = IO(Output(UInt(8.W)))
  out := in
}
```

```json
{
  "className": "UInt",
  "width": "8",
  "binding": "port",
  "direction": "input",
  "sourceLoc": "MyModule.scala:3"
}
```

### `circt_debug_moduleinfo`

Emits module-level information including constructor parameters.

**JSON Schema:**

```json
{
  "kind":       "module",   // required: literal value
  "className":  String,     // required: Scala class name
  "name":       String,     // required: elaborated RTL module name
  "ctorParams": {           // optional: map of primitive constructor params
    "<param>": <JSON primitive>  // number, bool, or string
  }
}
```

> **Note on parameter names**: Due to Scala 2 AST limitations, constructor
> parameter names are not available at compile time. Parameters are serialized
> with positional keys `"arg0"`, `"arg1"`, etc. Consuming tools must correlate
> parameter positions with the class definition to recover semantic names.

### Downstream Consumers

- **Tywaves**: Uses debug metadata for waveform annotation
- **HGDB**: Hardware debug database generation
- **hw-debug-info.json**: Unified debug information export

**Note:** Downstream tools rely on required fields being present and optional fields
being absent (not null) when not applicable.
