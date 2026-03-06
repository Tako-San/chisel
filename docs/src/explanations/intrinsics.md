---
layout: docs
title:  "Intrinsics"
section: "chisel3"
---

# Intrinsics

Chisel intrinsics express implementation-defined functionality. Use `Intrinsic` for statements, `IntrinsicExpr` for expressions.

```scala mdoc:invisible
import chisel3._
import chisel3.experimental.fromStringToStringParam
```

```scala mdoc:compile-only
class Foo extends RawModule {
  val myresult = IntrinsicExpr("MyIntrinsic", UInt(32.W), "STRING" -> "test")(3.U, 5.U)
}
```

## Debug Intrinsics

Debug intrinsics emit JSON metadata in FIRRTL. Schema `"MAJOR.MINOR"`: MINOR = backward compatible, MAJOR = breaking.

> **Scala 3**: `-P:chiselplugin:emitDebugTypeInfo` has no effect. `sourceLoc`, `params`, `ctorParams` are `"unknown"`/absent.

```bash
scalac -P:chiselplugin:emitDebugTypeInfo MyModule.scala
circt chisel --emit-debug-type-info MyModule.scala
```

### `circt_debug_typetag`

Signal type info. `width`: bit width or `"inferred"`. `binding`: `"port"|"reg"|"wire"|"memport"|"unknown"`. `direction`: `"input"|"output"|"flip"|"unspecified"`. Bundle nesting >32 → `"__truncated": true`.

```json
{ "className": String,  "width": String,  "binding": String,  "direction": String,  "sourceLoc": String,  "params": String,  "fields": Object,  "vecLength": Number,  "element": Object,  "enumType": String }
```

### `circt_debug_moduleinfo`

Module info including constructor parameters. `ctorParams` uses positional keys (`"arg0"`, `"arg1"`).

```json
{ "schemaVersion": "1.0",  "kind": "module",  "className": String,  "name": String,  "sourceLoc": String,  "ctorParams": Object }
```

### `circt_debug_enumdef`

Enum definitions (once per circuit). `valueStr` for BigInt precision. Consumers must run `OperationPass<CircuitOp>` to pre-collect enumdefs.

```json
{ "name": String,  "variants": [{ "name": String,  "value": Number,  "valueStr": String }] }
```

### `circt_debug_meminfo`

Memory definitions (`info` payload, `memName` matches `firrtl.mem sym_name`). `memoryKind`: `"Mem"|"SyncReadMem"|"SeqMem"`. Consumer: after `InferWidths`, before `FlattenMemory` and `LowerFIRRTLToHW`.

```json
{ "kind": "mem",  "name": String,  "memoryKind": String,  "dataType": Object,  "depth": String,  "sourceLoc": String,  "readUnderWrite": String }
```

### Field Keys

| Key | Meaning | Context |
|-----|---------|---------|
| `className`, `width` | Type, bit width | All |
| `kind` | Structural kind | Aggregates |
| `enumType` | ChiselEnum name | Enum fields |
| `fields`, `vecLength`, `element` | Record/Vec | Record, Vec |
| `depth` | Memory depth (string) | meminfo, sram |

### Downstream Consumers

- **Tywaves**: Waveform annotation
- **HGDB**: Debug database
- **hw-debug-info.json**: Unified debug export