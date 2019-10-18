package vsys.network

import java.net.InetSocketAddress

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import vsys.utils.ScorexLogging

import scala.concurrent.duration.FiniteDuration
import scala.util.DynamicVariable

class PeerSynchronizer(peerDatabase: PeerDatabase, peerRequestInterval: FiniteDuration)
  extends ChannelInboundHandlerAdapter with ScorexLogging {

  private val peersRequested = new DynamicVariable(false)
  private val declaredAddress = new DynamicVariable(Option.empty[InetSocketAddress])

  def requestPeers(ctx: ChannelHandlerContext): Unit = if (ctx.channel().isActive) {
    log.trace(s"${id(ctx)} Requesting peers")
    peersRequested.value = true
    ctx.writeAndFlush(GetPeers)

    ctx.executor().schedule(peerRequestInterval) {
      requestPeers(ctx)
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    declaredAddress.value.foreach(peerDatabase.touch)
    msg match {
      case hs: Handshake =>
        hs.declaredAddress.foreach { rda =>
          if (rda.getAddress == ctx.remoteAddress.getAddress) {
            log.trace(s"${id(ctx)} Touching declared address")
            peerDatabase.touch(rda)
            declaredAddress.value = Some(rda)
          } else {
            log.debug(s"${id(ctx)} Declared address $rda does not match actual remote address")
          }
        }
        requestPeers(ctx)
        super.channelRead(ctx, msg)
      case GetPeers =>
        log.debug(s"${id(ctx)} Sending known peers: ${peerDatabase.knownPeers.mkString("[", ", ", "]")}")
        ctx.writeAndFlush(KnownPeers(peerDatabase.knownPeers.keys.toSeq))
      case KnownPeers(peers) if peersRequested.value =>
        peersRequested.value = false
        log.trace(s"${id(ctx)} Got known peers: ${peers.mkString("[", ", ", "]")}")
        peers.foreach(peerDatabase.addCandidate)
      case KnownPeers(peers) =>
        log.debug(s"${id(ctx)} Got unexpected list of known peers containing ${peers.size} entries")
      case _ =>
        super.channelRead(ctx, msg)
    }
  }
}
