package org.encryfoundation.wallet.transaction

import io.circe.Encoder
import io.circe.syntax._
import io.iohk.iodb.ByteArrayWrapper
import org.encryfoundation.prismlang.compiler.{CompiledContract, CompiledContractSerializer}
import org.encryfoundation.prismlang.core.{Ast, Types}
import org.encryfoundation.prismlang.lib.predefined.signature.CheckSig
import org.encryfoundation.wallet.account.Account
import scorex.crypto.encode.{Base16, Base58}
import scorex.crypto.hash.{Blake2b256, Digest32}

import scala.util.Try

case class EncryProposition(contract: CompiledContract) {

//  def canUnlock(ctx: Context, proofs: Seq[Proof]): Boolean = {
//    val env: List[(Option[String], PValue)] =
//      if (contract.args.isEmpty) List.empty
//      else List((None, ctx.transaction.asVal), (None, ctx.state.asVal)) ++ proofs.map(proof => (proof.tagOpt, proof.value))
//    val args: List[(String, PValue)] = contract.args.map { case (name, tpe) =>
//      env.find(_._1.contains(name)).orElse(env.find(_._2.tpe == tpe)).map(elt => name -> elt._2)
//        .getOrElse(throw new Exception("Not enough arguments for contact")) }
//    Evaluator.initializedWith(args).eval[Boolean](contract.script)
//  }

  lazy val bytes: Array[Byte] = EncryProposition.Serializer.toBytes(this)

  lazy val contractHash: Digest32 = Blake2b256.hash(contract.bytes)

  def isOpen: Boolean = ByteArrayWrapper(contractHash) == ByteArrayWrapper(EncryProposition.open.contractHash)

  def isHeightLockedAt(height: Int): Boolean =
    ByteArrayWrapper(contractHash) == ByteArrayWrapper(EncryProposition.heightLocked(height).contractHash)

  def isLockedByAccount(account: String): Boolean =
    ByteArrayWrapper(contractHash) == ByteArrayWrapper(EncryProposition.accountLock(account).contractHash)
}

object EncryProposition {

  import org.encryfoundation.prismlang.core.Ast._

  case object UnlockFailedException extends Exception("Unlock failed")

  implicit val jsonEncoder: Encoder[EncryProposition] = (p: EncryProposition) => Map(
    "script" -> Base58.encode(p.contract.bytes).asJson
  ).asJson

  def open: EncryProposition = EncryProposition(CompiledContract(List.empty, Ast.Expr.True))

  def heightLocked(height: Int): EncryProposition = EncryProposition(
    CompiledContract(
      List("state" -> Types.EncryState),
      Expr.If(
        Expr.Compare(
          Expr.Attribute(
            Expr.Name(
              Ast.Ident("state"),
              Types.EncryState
            ),
            Ast.Ident("height"),
            Types.PInt
          ),
          List(Ast.CompOp.GtE),
          List(Expr.IntConst(height.toLong))
        ),
        Expr.True,
        Expr.False,
        Types.PBoolean
      )
    )
  )

  def accountLock(account: Account): EncryProposition = EncryProposition(
    CompiledContract(
      List("tx" -> Types.EncryTransaction, "sig" -> Types.Signature25519),
      Expr.Call(
        Expr.Name(Ident("checkSig"), Types.PFunc(CheckSig.args.toList, Types.PBoolean)),
        List(
          Expr.Name(Ident("sig"), Types.Signature25519),
          Expr.Attribute(
            Expr.Name(Ident("tx"), Types.EncryTransaction),
            Ident("messageToSign"),
            Types.PCollection.ofByte
          ),
          Expr.Base16Str(Base16.encode(account.pubKey))
        ),
        Types.PBoolean
      )
    )
  )

  def accountLock(address: String): EncryProposition = accountLock(Account(address))

  object Serializer {
    def toBytes(obj: EncryProposition): Array[Byte] = obj.contract.bytes
    def parseBytes(bytes: Array[Byte]): Try[EncryProposition] = CompiledContractSerializer.parseBytes(bytes)
      .map(EncryProposition.apply)
  }
}
