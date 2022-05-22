package codecs

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import scala.scalanative.unsigned._
import scodec.bits._
import scodec.codecs._
import scodec.Codec

import crypto.{Crypto, PrivateKey, PublicKey, Signature}
import codecs.Protocol
import codecs.TlvCodecs._
import codecs.CommonCodecs._
import codecs.HostedChannelTags._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._

sealed trait HostedClientMessage
sealed trait HostedServerMessage
sealed trait HostedGossipMessage
sealed trait HostedPreimageMessage

case class InvokeHostedChannel(
    chainHash: ByteVector32,
    refundScriptPubKey: ByteVector,
    secret: ByteVector = ByteVector.empty
) extends HostedClientMessage {
  val finalSecret: ByteVector = secret.take(128)
}

case class InitHostedChannel(
    maxHtlcValueInFlightMsat: ULong,
    htlcMinimumMsat: MilliSatoshi,
    maxAcceptedHtlcs: Int,
    channelCapacityMsat: MilliSatoshi,
    initialClientBalanceMsat: MilliSatoshi,
    features: List[Int] = Nil
) extends HostedServerMessage

case class HostedChannelBranding(
    rgbColor: Color,
    pngIcon: Option[ByteVector],
    contactInfo: String
) extends HostedServerMessage

case class LastCrossSignedState(
    isHost: Boolean,
    refundScriptPubKey: ByteVector,
    initHostedChannel: InitHostedChannel,
    blockDay: Long,
    localBalanceMsat: MilliSatoshi,
    remoteBalanceMsat: MilliSatoshi,
    localUpdates: Long,
    remoteUpdates: Long,
    incomingHtlcs: List[UpdateAddHtlc],
    outgoingHtlcs: List[UpdateAddHtlc],
    remoteSigOfLocal: Signature,
    localSigOfRemote: Signature
) extends HostedServerMessage
    with HostedClientMessage {
  lazy val reverse: LastCrossSignedState =
    copy(
      isHost = !isHost,
      localUpdates = remoteUpdates,
      remoteUpdates = localUpdates,
      localBalanceMsat = remoteBalanceMsat,
      remoteBalanceMsat = localBalanceMsat,
      remoteSigOfLocal = localSigOfRemote,
      localSigOfRemote = remoteSigOfLocal,
      incomingHtlcs = outgoingHtlcs,
      outgoingHtlcs = incomingHtlcs
    )

  lazy val hostedSigHash: ByteVector32 = {
    val inPayments = incomingHtlcs.map(add =>
      LightningMessageCodecs.updateAddHtlcCodec
        .encode(add)
        .require
        .toByteVector
    )
    val outPayments = outgoingHtlcs.map(add =>
      LightningMessageCodecs.updateAddHtlcCodec
        .encode(add)
        .require
        .toByteVector
    )
    val hostFlag = if (isHost) 1 else 0

    Crypto.sha256(
      refundScriptPubKey ++
        Protocol.writeUInt64(
          initHostedChannel.channelCapacityMsat.toLong,
          ByteOrder.LITTLE_ENDIAN
        ) ++
        Protocol.writeUInt64(
          initHostedChannel.initialClientBalanceMsat.toLong,
          ByteOrder.LITTLE_ENDIAN
        ) ++
        Protocol.writeUInt32(blockDay, ByteOrder.LITTLE_ENDIAN) ++
        Protocol
          .writeUInt64(localBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol
          .writeUInt64(remoteBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(localUpdates, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(remoteUpdates, ByteOrder.LITTLE_ENDIAN) ++
        inPayments.foldLeft(ByteVector.empty) { case (acc, htlc) =>
          acc ++ htlc
        } ++
        outPayments.foldLeft(ByteVector.empty) { case (acc, htlc) =>
          acc ++ htlc
        } :+
        hostFlag.toByte
    )
  }

  def verifyRemoteSig(pubKey: PublicKey): Boolean =
    Crypto.verifySignature(hostedSigHash, remoteSigOfLocal, pubKey)

  def withLocalSigOfRemote(priv: PrivateKey): LastCrossSignedState = {
    val localSignature = Crypto.sign(reverse.hostedSigHash, priv)
    copy(localSigOfRemote = localSignature)
  }

  def stateUpdate: StateUpdate =
    StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote)

  def stateOverride: StateOverride =
    StateOverride(
      blockDay,
      localBalanceMsat,
      localUpdates,
      remoteUpdates,
      localSigOfRemote
    )
}

case class StateUpdate(
    blockDay: Long,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: Signature
) extends HostedServerMessage
    with HostedClientMessage

case class StateOverride(
    blockDay: Long,
    localBalanceMsat: MilliSatoshi,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: Signature
) extends HostedServerMessage

case class AnnouncementSignature(
    nodeSignature: Signature,
    wantsReply: Boolean
) extends HostedGossipMessage

case class ResizeChannel(
    newCapacity: Satoshi,
    clientSig: Signature = ByteVector64.Zeroes
) extends HostedClientMessage {
  def isRemoteResized(remote: LastCrossSignedState): Boolean =
    newCapacity.toMilliSatoshi == remote.initHostedChannel.channelCapacityMsat

  def sign(priv: PrivateKey): ResizeChannel = ResizeChannel(
    clientSig = Crypto.sign(Crypto.sha256(sigMaterial), priv),
    newCapacity = newCapacity
  )

  def verifyClientSig(pubKey: PublicKey): Boolean =
    Crypto.verifySignature(Crypto.sha256(sigMaterial), clientSig, pubKey)

  lazy val sigMaterial: ByteVector = {
    val bin = new Array[Byte](8)
    val buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(newCapacity.toLong)
    ByteVector.view(bin)
  }
  lazy val newCapacityMsatU64: ULong = newCapacity.toMilliSatoshi.toLong.toULong
}

case class AskBrandingInfo(chainHash: ByteVector32) extends HostedClientMessage

case class QueryPublicHostedChannels(chainHash: ByteVector32)
    extends HostedGossipMessage {}

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32)
    extends HostedGossipMessage {}

// Queries
case class QueryPreimages(hashes: List[ByteVector32] = Nil)
    extends HostedPreimageMessage {}

case class ReplyPreimages(preimages: List[ByteVector32] = Nil)
    extends HostedPreimageMessage {}

// BOLT messages (used with nonstandard tag numbers)
case class Error(
    channelId: ByteVector32,
    data: ByteVector,
    tlvStream: TlvStream[ErrorTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage {
  def toAscii: String = if (data.toArray.forall(ch => ch >= 32 && ch < 127))
    new String(data.toArray, StandardCharsets.US_ASCII)
  else "n/a"
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error =
    Error(channelId, ByteVector.view(msg.getBytes(StandardCharsets.US_ASCII)))
}

sealed trait ErrorTlv extends Tlv

object ErrorTlv {
  val errorTlvCodec: Codec[TlvStream[ErrorTlv]] = tlvStream(
    discriminated[ErrorTlv].by(varint)
  )
}

case class UpdateAddHtlc(
    channelId: ByteVector32,
    id: ULong,
    amountMsat: MilliSatoshi,
    paymentHash: ByteVector32,
    cltvExpiry: CltvExpiry,
    onionRoutingPacket: ByteVector,
    tlvStream: TlvStream[UpdateAddHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage

case class UpdateFulfillHtlc(
    channelId: ByteVector32,
    id: ULong,
    paymentPreimage: ByteVector32,
    tlvStream: TlvStream[UpdateFulfillHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage

case class UpdateFailHtlc(
    channelId: ByteVector32,
    id: ULong,
    reason: ByteVector,
    tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage

case class UpdateFailMalformedHtlc(
    channelId: ByteVector32,
    id: ULong,
    onionHash: ByteVector32,
    failureCode: Int,
    tlvStream: TlvStream[UpdateFailMalformedHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage

case class ChannelAnnouncement(
    nodeSignature1: Signature,
    nodeSignature2: Signature,
    bitcoinSignature1: Signature,
    bitcoinSignature2: Signature,
    features: Features[Feature],
    chainHash: ByteVector32,
    shortChannelId: ShortChannelId,
    nodeId1: PublicKey,
    nodeId2: PublicKey,
    bitcoinKey1: PublicKey,
    bitcoinKey2: PublicKey,
    tlvStream: TlvStream[ChannelAnnouncementTlv] = TlvStream.empty
) extends HostedGossipMessage
case class ChannelUpdate(
    signature: Signature,
    chainHash: ByteVector32,
    shortChannelId: ShortChannelId,
    timestamp: TimestampSecond,
    channelFlags: ChannelUpdate.ChannelFlags,
    cltvExpiryDelta: CltvExpiryDelta,
    htlcMinimumMsat: MilliSatoshi,
    feeBaseMsat: MilliSatoshi,
    feeProportionalMillionths: Long,
    htlcMaximumMsat: Option[MilliSatoshi],
    tlvStream: TlvStream[ChannelUpdateTlv] = TlvStream.empty
) extends HostedServerMessage
    with HostedClientMessage
    with HostedGossipMessage {
  def messageFlags: Byte = if (htlcMaximumMsat.isDefined) 1 else 0
  def toStringShort: String =
    s"cltvExpiryDelta=$cltvExpiryDelta,feeBase=$feeBaseMsat,feeProportionalMillionths=$feeProportionalMillionths"
}

object ChannelUpdate {
  case class ChannelFlags(isEnabled: Boolean, isNode1: Boolean)
  object ChannelFlags {}
}
