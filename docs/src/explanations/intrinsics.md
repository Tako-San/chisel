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

Debug intrinsics emit parameterized metadata in FIRRTL using native parameters instead of JSON schemas.

> **Scala 3**: `-P:chiselplugin:emitDebugTypeInfo` has no effect. `sourceFile`, `sourceLine`, `params`, `ctorParams` are `"unknown"`/absent.

```bash
scalac -P:chiselplugin:emitDebugTypeInfo MyModule.scala
circt chisel --emit-debug-type-info MyModule.scala
```

### `circt_debug_typetag` parameters

Signal type info. `binding`: `"port"|"reg"|"wire"|"memport"|"unknown"`. `direction`: `"input"|"output"|"flip"|"unspecified"`. `width` and `sourceLine` use sentinel values (-1) to indicate unknown/inferred values.

| Param        | Type        | Sentinel  | Notes                          |
|--------------|-------------|-----------|--------------------------------|
| `className`  | StringAttr  | —         |                                |
| `width`      | IntegerAttr | `-1`      | -1 = inferred                  |
| `binding`    | StringAttr  | `unknown` | port/reg/wire/memport/node     |
| `direction`  | StringAttr  | `unspecified` |                            |
| `sourceFile` | StringAttr  | `""`      | empty = unknown                |
| `sourceLine` | IntegerAttr | `-1`      | -1 = unknown                   |
| `params`     | StringAttr  | absent    | optional: Chisel ctor params   |
| `enumType`   | StringAttr  | absent    | optional: simple enum name     |
| `enumTypeFqn`| StringAttr  | absent    | optional: fully-qualified name |
| `kind`       | StringAttr  | absent    | optional: MixedVec etc.        |
| `vecLength`  | IntegerAttr | absent    | optional: present for Vec      |
| `fields`     | StringAttr  | absent    | optional: JSON Record fields   |
| `element`    | StringAttr  | absent    | optional: JSON Vec elem type   |

### `circt_debug_moduleinfo` parameters

Module info including constructor parameters.

| Param       | Type       | Sentinel | Notes                          |
|-------------|------------|----------|--------------------------------|
| `className` | StringAttr | —        |                                |
| `name`      | StringAttr | —        | module name                     |
| `sourceFile`| StringAttr | `""`     | empty = unknown                |
| `sourceLine`| IntegerAttr | `-1`     | -1 = unknown                   |
| `ctorParams`| StringAttr  | absent   | optional: JSON constructor params|

### `circt_debug_enumdef` parameters

Enum definitions (once per circuit). Consumers must run `OperationPass<CircuitOp>` to pre-collect enumdefs.

| Param     | Type       | Sentinel | Notes                    |
|-----------|------------|----------|--------------------------|
| `name`    | StringAttr | —        |                          |
| `fqn`     | StringAttr | —        | fully-qualified name     |
| `variants`| StringAttr | —        | JSON array of enum values|

### `circt_debug_meminfo` parameters

Memory definitions. `memName` matches `firrtl.mem sym_name`. `memoryKind`: `"Mem"|"SyncReadMem"|"SeqMem"`. Consumer: after `InferWidths`, before `FlattenMemory` and `LowerFIRRTLToHW`.

| Param         | Type       | Sentinel | Notes                          |
|---------------|------------|----------|--------------------------------|
| `memName`     | StringAttr | —        | memory instance name           |
| `memoryKind`  | StringAttr | —        | Memory/SeqMemory/...           |
| `depth`       | IntegerAttr | —        |                                |
| `sourceFile`  | StringAttr | `""`     | empty = unknown                |
| `sourceLine`  | IntegerAttr | `-1`     | -1 = unknown                   |
| `dataType`    | StringAttr  | —        | JSON: type description         |
| `readUnderWrite`| StringAttr | absent | optional: for SyncReadMem      |

### Parameter Notes

| Param | Meaning | Context |
|-------|---------|---------|
| `className`, `width` | Type, bit width | typetag |
| `kind` | Structural kind | typetag aggregates |
| `enumType`, `enumTypeFqn` | ChiselEnum name | typetag enum fields |
| `fields`, `vecLength`, `element` | Record/Vec | typetag |
| `depth` | Memory depth | meminfo |
| `sourceFile`, `sourceLine` | Source location | All debug intrinsics |
| `binding`, `direction` | Signal binding & direction | typetag |

### Downstream Consumers

- **Tywaves**: Waveform annotation
- **HGDB**: Debug database
- **hw-debug-info.json**: Unified debug export
