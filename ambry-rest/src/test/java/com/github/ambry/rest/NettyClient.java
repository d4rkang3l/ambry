package com.github.ambry.rest;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Netty client to send requests and receive responses.
 */
public class NettyClient {
  /**
   * To record any exceptions at startup
   */
  private final AtomicReference<Throwable> cause = new AtomicReference<Throwable>();
  private final NettyClientDeployer deployer;

  public NettyClient(int serverPort, LinkedBlockingQueue<HttpObject> contentQueue,
      LinkedBlockingQueue<HttpObject> responseQueue) {
    deployer = new NettyClientDeployer(serverPort, contentQueue, responseQueue, cause);
  }

  /**
   * Starts the netty client. Returns after startup is FULLY complete.
   * <p/>
   * For now all content has to be loaded before start() is called. Once start is called, all the contents in the
   * content queue are sent to the server and any that are enqueued after this call are ignored.
   * @throws InstantiationException
   */
  public void start()
      throws InstantiationException {
    try {
      new Thread(deployer).start();
      if (!deployer.awaitStartup(30, TimeUnit.SECONDS)) {
        throw new InstantiationException("Client did not start in 30 seconds");
      }
    } catch (InterruptedException e) {
      throw new InstantiationException("the await startup was interrupted. Client may not have started");
    }
  }

  /**
   * Shuts down the netty client. Returns after shutdown is FULLY complete.
   * @throws Exception
   */
  public void shutdown()
      throws Exception {
    deployer.shutdown();
  }

  /**
   * Deploys the netty client as a separate thread.
   */
  private class NettyClientDeployer implements Runnable {
    private final CountDownLatch startupComplete = new CountDownLatch(1);
    private EventLoopGroup group = new NioEventLoopGroup();

    private final int serverPort;
    private final LinkedBlockingQueue<HttpObject> contentQueue;
    private final LinkedBlockingQueue<HttpObject> responseQueue;
    private final AtomicReference<Throwable> cause;

    public NettyClientDeployer(int serverPort, LinkedBlockingQueue<HttpObject> contentQueue,
        LinkedBlockingQueue<HttpObject> responseQueue, AtomicReference<Throwable> cause) {
      this.serverPort = serverPort;
      this.contentQueue = contentQueue;
      this.responseQueue = responseQueue;
      this.cause = cause;
    }

    public void run() {
      try {
        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).option(ChannelOption.SO_KEEPALIVE, false)
            .option(ChannelOption.TCP_NODELAY, false).handler(new ChannelInitializer<SocketChannel>() {
          @Override
          public void initChannel(SocketChannel ch)
              throws Exception {
            ch.pipeline().addLast(new HttpClientCodec()).addLast(new ChunkedWriteHandler())
                .addLast(new CommunicationHandler(contentQueue, responseQueue, cause)); // custom handler
          }
        });

        ChannelFuture f = b.connect("localhost", serverPort).sync();
        startupComplete.countDown();
        f.channel().closeFuture().sync();
      } catch (Exception e) {
        cause.set(e);
        startupComplete.countDown();
      }
    }

    /**
     * Waits until client is deployed.
     * @param timeout
     * @param timeUnit
     * @return
     * @throws InterruptedException
     */
    public boolean awaitStartup(long timeout, TimeUnit timeUnit)
        throws InterruptedException {
      return startupComplete.await(timeout, timeUnit);
    }

    /**
     * Shuts down netty client. Returns after shutdown is complete.
     * @throws Exception
     */
    public void shutdown()
        throws Exception {
      if (group != null) {
        group.shutdownGracefully();
        if (!group.awaitTermination(30, TimeUnit.SECONDS)) {
          throw new Exception("Client did not shutdown within timeout");
        } else {
          group = null;
        }
      }
    }
  }
}

/**
 * Custom handler that sends out request and receives responses.
 */
class CommunicationHandler extends SimpleChannelInboundHandler<Object> {
  private final AtomicReference<Throwable> cause;
  private final LinkedBlockingQueue<HttpObject> contentQueue;
  private final LinkedBlockingQueue<HttpObject> responseQueue;

  public CommunicationHandler(LinkedBlockingQueue<HttpObject> contentQueue,
      LinkedBlockingQueue<HttpObject> responseQueue, AtomicReference<Throwable> cause) {
    this.cause = cause;
    this.contentQueue = contentQueue;
    this.responseQueue = responseQueue;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx)
      throws Exception {
    // TODO: This can be upgraded later to allow async pushing but this will do for now.
    while (!contentQueue.isEmpty()) {
      ctx.writeAndFlush(contentQueue.remove());
    }
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, Object in)
      throws Exception {
    if (in instanceof HttpObject) {
      ReferenceCountUtil.retain(in); // make sure that we increase refCnt.
      responseQueue.offer((HttpObject) in);
    } else {
      throw new IllegalStateException("Read object is not a HTTPObject");
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    ctx.close();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    this.cause.set(cause);
  }
}
