// SPDX-License-Identifier: Apache-2.0
package chisel3.stage.phases

import chisel3.debug.DebugIntrinsics
import chisel3.stage.ChiselCircuitAnnotation

import firrtl.options.{Dependency, Phase}
import firrtl.AnnotationSeq

class AddDebugIntrinsics extends Phase {
  override def prerequisites = Seq(Dependency[Elaborate])
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq(Dependency[Convert], Dependency[AddDedupGroupAnnotations])
  override def invalidates(a: Phase) = false

  def transform(annotations: AnnotationSeq): AnnotationSeq = annotations.map {
    case a: ChiselCircuitAnnotation =>
      DebugIntrinsics.generate(a.elaboratedCircuit._circuit)
      a
    case a => a
  }
}
