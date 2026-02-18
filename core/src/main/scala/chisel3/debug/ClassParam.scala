// SPDX-License-Identifier: Apache-2.0
package chisel3.debug

/** Represents the parameters of a class constructor.
  *
  * @param name     The name of the parameter
  * @param typeName The Scala/JVM type of the parameter
  * @param value    Serialized value; `None` if reflection could not retrieve it
  */
case class ClassParam(name: String, typeName: String, value: Option[String])
