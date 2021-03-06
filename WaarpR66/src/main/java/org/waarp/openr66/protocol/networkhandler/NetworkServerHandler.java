/*
 * This file is part of Waarp Project (named also Waarp or GG).
 *
 *  Copyright (c) 2019, Waarp SAS, and individual contributors by the @author
 *  tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 *  All Waarp Project is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 * Waarp . If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.protocol.networkhandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.ReadTimeoutException;
import org.waarp.common.crypto.ssl.WaarpSslUtility;
import org.waarp.common.database.DbSession;
import org.waarp.common.database.exception.WaarpDatabaseNoConnectionException;
import org.waarp.common.logging.SysErrLogger;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.common.utility.WaarpShutdownHook;
import org.waarp.openr66.protocol.configuration.Configuration;
import org.waarp.openr66.protocol.exception.OpenR66Exception;
import org.waarp.openr66.protocol.exception.OpenR66ExceptionTrappedFactory;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolBusinessNoWriteBackException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolPacketException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolRemoteShutdownException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolSystemException;
import org.waarp.openr66.protocol.localhandler.LocalChannelReference;
import org.waarp.openr66.protocol.localhandler.LocalServerHandler;
import org.waarp.openr66.protocol.localhandler.packet.AbstractLocalPacket;
import org.waarp.openr66.protocol.localhandler.packet.ConnectionErrorPacket;
import org.waarp.openr66.protocol.localhandler.packet.KeepAlivePacket;
import org.waarp.openr66.protocol.localhandler.packet.LocalPacketCodec;
import org.waarp.openr66.protocol.localhandler.packet.LocalPacketFactory;
import org.waarp.openr66.protocol.networkhandler.packet.NetworkPacket;
import org.waarp.openr66.protocol.utils.ChannelCloseTimer;
import org.waarp.openr66.protocol.utils.ChannelUtils;

import java.net.BindException;
import java.net.SocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.waarp.common.database.DbConstant.*;

/**
 * Network Server Handler (Requester side)
 */
public class NetworkServerHandler
    extends SimpleChannelInboundHandler<NetworkPacket> {
  /**
   * Internal Logger
   */
  private static final WaarpLogger logger =
      WaarpLoggerFactory.getLogger(NetworkServerHandler.class);

  /**
   * The associated Remote Address
   */
  private SocketAddress remoteAddress;
  /**
   * The associated NetworkChannelReference
   */
  private NetworkChannelReference networkChannelReference;
  /**
   * The Database connection attached to this NetworkChannelReference shared
   * among all associated LocalChannels
   */
  private DbSession dbSession;
  /**
   * Does this Handler is for SSL
   */
  protected boolean isSSL;
  /**
   * Is this Handler a server side
   */
  protected final boolean isServer;
  /**
   * To handle the keep alive
   */
  private final AtomicInteger keepAlivedSent = new AtomicInteger(0);
  /**
   * Is this network connection being refused (black listed)
   */
  protected volatile boolean isBlackListed;

  /**
   * @param isServer
   */
  public NetworkServerHandler(boolean isServer) {
    this.isServer = isServer;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    try {
      if (networkChannelReference != null) {
        if (networkChannelReference.nbLocalChannels() > 0) {
          logger.info("Network Channel Closed: {} LocalChannels Left: {}",
                      ctx.channel().id(),
                      networkChannelReference.nbLocalChannels());
          // Give an extra time if necessary to let the local channel being closed
          try {
            Thread.sleep(Configuration.RETRYINMS * 2);
          } catch (final InterruptedException e1) {//NOSONAR
            SysErrLogger.FAKE_LOGGER.ignoreLog(e1);
          }
        }
        try {
          NetworkTransaction.closedNetworkChannel(networkChannelReference);
        } catch (RejectedExecutionException e) {
          logger.debug(e);
        }
      } else {
        if (remoteAddress == null) {
          remoteAddress = ctx.channel().remoteAddress();
        }
        try {
          NetworkTransaction.closedNetworkChannel(remoteAddress);
        } catch (RejectedExecutionException e) {
          logger.debug(e);
        }
      }
      // Now force the close of the database after a wait
      if (dbSession != null && admin != null && admin.getSession() != null &&
          !dbSession.equals(admin.getSession())) {
        dbSession.forceDisconnect();
        dbSession = null;
      }
    } catch (RejectedExecutionException e) {
      logger.debug(e);
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    final Channel netChannel = ctx.channel();
    remoteAddress = netChannel.remoteAddress();
    logger.debug(
        "Will the Connection be refused if Partner is BlackListed from " +
        remoteAddress);
    if (NetworkTransaction.isBlacklisted(netChannel)) {
      logger.warn("Connection refused since Partner is BlackListed from " +
                  remoteAddress);
      isBlackListed = true;
      if (Configuration.configuration.getR66Mib() != null) {
        Configuration.configuration.getR66Mib().notifyError(
            "Black Listed connection temptative", "During connection");
      }
      // close immediately the connection
      WaarpSslUtility.closingSslChannel(netChannel);
      return;
    }
    try {
      networkChannelReference =
          NetworkTransaction.addNetworkChannel(netChannel, isSSL);
    } catch (final OpenR66ProtocolRemoteShutdownException e2) {
      logger.warn("Connection refused since Partner is in Shutdown from " +
                  remoteAddress + " : {}", e2.getMessage());
      isBlackListed = true;
      // close immediately the connection
      WaarpSslUtility.closingSslChannel(netChannel);
      return;
    }
    try {
      if (admin.isCompatibleWithThreadSharedConnexion()) {
        dbSession = new DbSession(admin, false);
        dbSession.useConnection();
      } else {
        logger.debug("DbSession will be adjusted on LocalChannelReference");
        dbSession = admin.getSession();
      }
    } catch (final WaarpDatabaseNoConnectionException e1) {
      // Cannot connect so use default connection
      logger.warn("Use default database connection");
      dbSession = admin.getSession();
    }
    logger.debug("Network Channel Connected: {} ", ctx.channel().id());
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
      throws Exception {
    if (Configuration.configuration.isShutdown()) {
      return;
    }
    if (evt instanceof IdleStateEvent) {
      if (networkChannelReference != null && networkChannelReference
                                                 .checkLastTime(
                                                     Configuration.configuration
                                                         .getTimeoutCon() *
                                                     2) <= 0) {
        keepAlivedSent.set(0);
        return;
      }
      if (keepAlivedSent.get() > 0) {
        if (networkChannelReference != null &&
            networkChannelReference.nbLocalChannels() > 0 &&
            keepAlivedSent.get() < 5) {
          // ignore this time
          keepAlivedSent.getAndIncrement();
          return;
        }
        logger.error("Not getting KAlive: closing channel");
        if (Configuration.configuration.getR66Mib() != null) {
          Configuration.configuration.getR66Mib()
                                     .notifyWarning("KeepAlive get no answer",
                                                    "Closing network connection");
        }
        ChannelCloseTimer.closeFutureChannel(ctx.channel());
      } else {
        keepAlivedSent.set(1);
        final KeepAlivePacket keepAlivePacket = new KeepAlivePacket();
        final NetworkPacket response =
            new NetworkPacket(ChannelUtils.NOCHANNEL, ChannelUtils.NOCHANNEL,
                              keepAlivePacket, null);
        logger.info("Write KAlive");
        ctx.channel().writeAndFlush(response);
      }
    }
  }

  public void setKeepAlivedSent() {
    keepAlivedSent.set(0);
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, NetworkPacket msg)
      throws Exception {
    if (isBlackListed) {
      // ignore message since close on going
      msg.clear();
      return;
    }
    final Channel channel = ctx.channel();
    if (msg.getCode() == LocalPacketFactory.NOOPPACKET) {
      if (networkChannelReference != null) {
        networkChannelReference.useIfUsed();
      }
      msg.clear();
      // Do nothing
      return;
    } else if (msg.getCode() == LocalPacketFactory.CONNECTERRORPACKET) {
      logger.debug("NetworkRecv: {}", msg);
      // Special code to STOP here
      if (msg.getLocalId() == ChannelUtils.NOCHANNEL) {
        final int nb = networkChannelReference.nbLocalChannels();
        if (nb > 0) {
          logger.warn(
              "Temptative of connection failed but still some connection are there so not closing the server channel immediately: " +
              nb);
          NetworkTransaction
              .shuttingDownNetworkChannel(networkChannelReference);
          return;
        }
        // No way to know what is wrong: close all connections with
        // remote host
        logger.error(
            "Will close NETWORK channel, Cannot continue connection with remote Host: " +
            msg + " : " + channel.remoteAddress() + " : " + nb);
        WaarpSslUtility.closingSslChannel(channel);
        msg.clear();
        return;
      }
    } else if (msg.getCode() == LocalPacketFactory.KEEPALIVEPACKET) {
      if (networkChannelReference != null) {
        networkChannelReference.useIfUsed();
      }
      keepAlivedSent.set(0);
      try {
        final KeepAlivePacket keepAlivePacket =
            (KeepAlivePacket) LocalPacketCodec
                .decodeNetworkPacket(msg.getBuffer());
        if (keepAlivePacket.isToValidate()) {
          keepAlivePacket.validate();
          final NetworkPacket response =
              new NetworkPacket(ChannelUtils.NOCHANNEL, ChannelUtils.NOCHANNEL,
                                keepAlivePacket, null);
          logger.info("Answer KAlive");
          ctx.writeAndFlush(response);
        } else {
          logger.info("Get KAlive");
        }
      } catch (final OpenR66ProtocolPacketException ignored) {
        // nothing
      }
      msg.clear();
      return;
    }
    logger.trace("TRACE ID GET MSG: {}", msg);
    networkChannelReference.use();
    LocalChannelReference localChannelReference;
    if (msg.getLocalId() == ChannelUtils.NOCHANNEL) {
      localChannelReference = NetworkTransaction
          .createConnectionFromNetworkChannelStartup(networkChannelReference,
                                                     msg, isSSL);
    } else {
      if (msg.getCode() == LocalPacketFactory.ENDREQUESTPACKET) {
        // Coming from remote
        try {
          localChannelReference =
              Configuration.configuration.getLocalTransaction()
                                         .getClient(msg.getRemoteId(),
                                                    msg.getLocalId());
        } catch (final OpenR66ProtocolSystemException e1) {
          // do not send anything since the packet is external
          try {
            logger.debug(
                "Cannot get LocalChannel while an end of request comes: {}",
                LocalPacketCodec.decodeNetworkPacket(msg.getBuffer()));
          } catch (final OpenR66ProtocolPacketException e2) {
            logger.debug(
                "Cannot get LocalChannel while an end of request comes: {}",
                msg.toString());
          }
          msg.clear();
          return;
        }
        // OK continue and send to the local channel
      } else if (msg.getCode() == LocalPacketFactory.CONNECTERRORPACKET) {
        // Not a local error but a remote one
        try {
          localChannelReference =
              Configuration.configuration.getLocalTransaction()
                                         .getClient(msg.getRemoteId(),
                                                    msg.getLocalId());
        } catch (final OpenR66ProtocolSystemException e1) {
          // do not send anything since the packet is external
          try {
            logger.debug(
                "Cannot get LocalChannel while an external error comes: {}",
                LocalPacketCodec.decodeNetworkPacket(msg.getBuffer()));
          } catch (final OpenR66ProtocolPacketException e2) {
            logger.debug(
                "Cannot get LocalChannel while an external error comes: {}",
                msg.toString());
          }
          msg.clear();
          return;
        }
        // OK continue and send to the local channel
      } else {
        try {
          localChannelReference =
              Configuration.configuration.getLocalTransaction()
                                         .getClient(msg.getRemoteId(),
                                                    msg.getLocalId());
        } catch (final OpenR66ProtocolSystemException e1) {
          if (remoteAddress == null) {
            remoteAddress = channel.remoteAddress();
          }
          if (NetworkTransaction.isShuttingdownNetworkChannel(remoteAddress) ||
              WaarpShutdownHook.isShutdownStarting()) {
            // ignore
            msg.clear();
            return;
          }
          // try to send later
          logger.debug(
              "Cannot get LocalChannel: " + msg + " due to " + e1.getMessage());
          final ConnectionErrorPacket error = new ConnectionErrorPacket(
              "Cannot get localChannel since localId is not found anymore",
              String.valueOf(msg.getLocalId()));
          writeError(channel, msg.getRemoteId(), msg.getLocalId(), error);
          msg.clear();
          return;
        }
      }
    }
    // check if not already in shutdown or closed
    if (NetworkTransaction.isShuttingdownNetworkChannel(remoteAddress) ||
        WaarpShutdownHook.isShutdownStarting()) {
      logger.debug("Cannot use LocalChannel since already in shutdown: " + msg);
      // ignore
      msg.clear();
      return;
    }
    LocalServerHandler.channelRead0(localChannelReference, msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    final Channel channel = ctx.channel();
    if (isBlackListed) {
      logger.info("While partner is blacklisted, Network Channel Exception: {}",
                  channel.id(), cause.getClass().getName() + " : " + cause);
      // ignore
      return;
    }
    logger.debug("Network Channel Exception: {}", channel.id(), cause);
    if (cause instanceof ReadTimeoutException) {
      final ReadTimeoutException exception = (ReadTimeoutException) cause;
      // No read for too long
      logger.error("ReadTimeout so Will close NETWORK channel {}",
                   exception.getClass().getName() + " : " +
                   exception.getMessage());
      ChannelCloseTimer.closeFutureChannel(channel);
      return;
    }
    if (cause instanceof BindException) {
      // received when not yet connected
      logger.debug("BindException");
      ChannelCloseTimer.closeFutureChannel(channel);
      return;
    }
    final OpenR66Exception exception = OpenR66ExceptionTrappedFactory
        .getExceptionFromTrappedException(channel, cause);
    if (exception != null) {
      if (exception instanceof OpenR66ProtocolBusinessNoWriteBackException) {
        if (networkChannelReference != null &&
            networkChannelReference.nbLocalChannels() > 0) {
          logger.debug("Network Channel Exception: {} {}", channel.id(),
                       exception.getClass().getName() + " : " +
                       exception.getMessage());
        }
        logger.debug("Will close NETWORK channel");
        ChannelCloseTimer.closeFutureChannel(channel);
        return;
      } else if (exception instanceof OpenR66ProtocolNoConnectionException) {
        logger.debug("Connection impossible with NETWORK channel {}",
                     exception.getClass().getName() + " : " +
                     exception.getMessage());
        channel.close();
        return;
      } else {
        logger.debug("Network Channel Exception: {} {}", channel.id(),
                     exception.getClass().getName() + " : " +
                     exception.getMessage());
      }
      final ConnectionErrorPacket errorPacket = new ConnectionErrorPacket(
          exception.getClass().getName() + " : " + exception.getMessage(),
          null);
      writeError(channel, ChannelUtils.NOCHANNEL, ChannelUtils.NOCHANNEL,
                 errorPacket);
      logger.debug("Will close NETWORK channel: {}",
                   exception.getClass().getName() + " : " +
                   exception.getMessage());
      ChannelCloseTimer.closeFutureChannel(channel);
    } else {
      // Nothing to do
    }
  }

  /**
   * Write error back to remote client
   *
   * @param channel
   * @param remoteId
   * @param localId
   * @param error
   */
  public static void writeError(Channel channel, Integer remoteId,
                                Integer localId, AbstractLocalPacket error) {
    NetworkPacket networkPacket = null;
    try {
      networkPacket = new NetworkPacket(localId, remoteId, error, null);
    } catch (final OpenR66ProtocolPacketException ignored) {
      // nothing
    }
    try {
      if (channel.isActive()) {
        channel.writeAndFlush(networkPacket).await(Configuration.WAITFORNETOP);
      }
    } catch (final InterruptedException e) {//NOSONAR
      SysErrLogger.FAKE_LOGGER.ignoreLog(e);
    }
  }

  /**
   * @return the dbSession
   */
  public DbSession getDbSession() {
    return dbSession;
  }

  /**
   * @return True if this Handler is for SSL
   */
  public boolean isSsl() {
    return isSSL;
  }
}
