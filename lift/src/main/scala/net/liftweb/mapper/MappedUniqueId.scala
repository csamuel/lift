package net.liftweb.mapper

/*
 * Copyright 2006-2008 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

import net.liftweb.mapper._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.{S, SHtml}
import scala.xml.{Elem, NodeSeq}
import net.liftweb.http.js._

class MappedUniqueId[T<:Mapper[T]](override val fieldOwner: T, override val maxLen: Int) extends MappedString[T](fieldOwner, maxLen) {
  override def writePermission_? = false
  override lazy val defaultValue = randomString(maxLen)

  def reset(): T = this(randomString(maxLen))
}

/**
  * A field that holds the birth year for the user
  */
class MappedBirthYear[T <: Mapper[T]](owner: T, minAge: Int) extends MappedInt[T](owner) {
  override def defaultValue = year(timeNow) - minAge

  override def _toForm: Can[NodeSeq] = {
    val end = (year(timeNow) - minAge)
    val start = end - 100
    Full(SHtml.selectObj((start to end).
		  toList.
		  reverse.
		  map(y => (y, y.toString)),
		  Full(is), this.set))
  }
}

class MappedGender[T <: Mapper[T]](owner: T) extends MappedEnum(owner, Genders) {
  override def defaultValue = Genders.Male
}

object Genders extends Enumeration {

  val Male = new I18NGender(1, "male")
  val Female = new I18NGender(2, "female")

  class I18NGender(id : Int, name: String) extends Val(id, name) {
    override def toString = {
      S.??(name)
    }
  }
}

class MappedStringIndex[T<:Mapper[T]](override val fieldOwner: T, override val maxLen: Int) extends MappedUniqueId[T](fieldOwner, maxLen) with IndexedField[String] {

  override def writePermission_? = false // not writable

  override def dbIndexed_? = true

  def defined_? = i_is_! ne null

  override def dbPrimaryKey_? = true

  override def dbDisplay_? = false

  def makeKeyJDBCFriendly(in: String) = in

  def convertKey(in: String): Can[String] = Can.legacyNullTest(in)
  def convertKey(in: Int): Can[String] = Full(in.toString)
  def convertKey(in: Long): Can[String] = Full(in.toString)
  def convertKey(in: AnyRef): Can[String] = Can.legacyNullTest(in).map(_.toString)
}


class MappedStringForeignKey[T<:Mapper[T],O<:KeyedMapper[String, O]](override val fieldOwner: T, foreign: => KeyedMetaMapper[String, O],override val maxLen: Int)
extends MappedString[T](fieldOwner, maxLen) with MappedForeignKey[String,T,O] with BaseForeignKey {
  def defined_? = i_is_! ne null

  type KeyType = String
  type KeyedForeignType = O
  type OwnerType = T

  override def jdbcFriendly(field: String) = i_is_!
  override def jdbcFriendly = i_is_!

  lazy val obj: Can[O] = if(defined_?) foreign.find(i_is_!) else Empty

  def dbKeyToTable: KeyedMetaMapper[String, O] = foreign
  def dbKeyToColumn = dbKeyToTable.primaryKeyField

  override def dbIndexed_? = true

  override def dbForeignKey_? = true


  def asSafeJs(obs: Can[KeyObfuscator]): JsExp =
    obs.map(o => JE.Str(o.obscure(dbKeyToTable, is))).openOr(JE.Str(is))

  /**
   * Called when Schemifier adds a foreign key.  Return a function that will be called when Schemifier
   * is done with the schemification.
   */
  def dbAddedForeignKey: Can[() => Unit] = Empty

  override def toString = if (defined_?) super.toString else "NULL"

  def set(v: Can[O]): T = {
    val toSet: String = v match {
      case Full(i) => i.primaryKeyField.is
      case _ => null
    }

    this(toSet)
  }

  def apply(v: O): T = this(v.primaryKeyField.is)

  def findFor(key: KeyType): List[OwnerType] = fieldOwner.getSingleton.findAll(By(this, key))

  def findFor(key: KeyedForeignType): List[OwnerType] = fieldOwner.getSingleton.findAll(By(this, key))

  /**
   * Given the driver type, return the string required to create the column in the database
   */
  override def fieldCreatorString(dbType: DriverType, colName: String): String = colName + " " + dbType.longForeignKeyColumnType

}
