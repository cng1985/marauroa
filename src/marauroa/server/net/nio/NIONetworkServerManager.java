package marauroa.server.net.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import marauroa.client.net.TCPThreadedNetworkClientManager;
import marauroa.common.Log4J;
import marauroa.common.game.RPAction;
import marauroa.common.net.Decoder;
import marauroa.common.net.Encoder;
import marauroa.common.net.InvalidVersionException;
import marauroa.common.net.Message;
import marauroa.common.net.MessageC2SAction;
import marauroa.common.net.MessageS2CInvalidMessage;
import marauroa.common.net.NetConst;
import marauroa.server.game.Statistics;
import marauroa.server.net.INetworkServerManager;
import marauroa.server.net.PacketValidator;

import org.apache.log4j.Logger;



public class NIONetworkServerManager extends Thread implements IWorker, INetworkServerManager {
	/** the logger instance. */
	private static final Logger logger = Log4J.getLogger(NIONetworkServerManager.class);
	
	/** We store the server for sending stuff. */
	private NioServer server;

	/** While keepRunning is true, we keep recieving messages */
	private boolean keepRunning;

	/** isFinished is true when the thread has really exited. */
	private boolean isFinished;

	/** A List of Message objects: List<Message> */
	private BlockingQueue<Message> messages;

	private Map<InetSocketAddress, SocketChannel> sockets = null;

	/** Statistics */
	private Statistics stats;

	/** checkes if the ip-address is banned */
	private PacketValidator packetValidator;

	private BlockingQueue<DataEvent> queue;
	
	private Encoder encoder;
	private Decoder decoder;
	
	public NIONetworkServerManager() throws IOException {
		Log4J.startMethod(logger, "NetworkServerManager");

		/* init the packet validater (which can now only check if the address is banned)*/
		packetValidator = new PacketValidator();
		keepRunning = true;
		isFinished = false;
		
		encoder=Encoder.get();
		decoder=Decoder.get();

		/* Because we access the list from several places we create a synchronized list. */
		messages = new LinkedBlockingQueue<Message>();
		stats = Statistics.getStatistics();
		queue = new LinkedBlockingQueue<DataEvent>();
		
		sockets=new HashMap<InetSocketAddress, SocketChannel>();

		logger.debug("NetworkServerManager started successfully");
		
		server=new NioServer(null, NetConst.marauroa_PORT, this);
		server.start();
	}

	public void setServer(NioServer server) {
		this.server=server;		
	}

	/** 
	 * This method notify the thread to finish it execution
	 */
	public void finish() {
		logger.info("shutting down NetworkServerManager");
		keepRunning = false;
		
		server.finish();
		interrupt();
		
		while (isFinished == false) {
			Thread.yield();
		}		

		logger.info("NetworkServerManager is down");
	}

	/** 
	 * This method returns a Message from the list or block for timeout milliseconds
	 * until a message is available or null if timeout happens.
	 *
	 * @param timeout timeout time in milliseconds
	 * @return a Message or null if timeout happens
	 */
	public synchronized Message getMessage(int timeout) {
		try {
			return messages.poll(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			/* If interrupted while waiting we just return null */
			return null;
		}
	}

	/** 
	 * This method blocks until a message is available
	 *
	 * @return a Message
	 */
	public synchronized Message getMessage() {
		try {
			return messages.take();
		} catch (InterruptedException e) {
			/* If interrupted while waiting we just return null */
			return null;
		}
	}

	/** Adds the channel to the map of address, channel when the client connects */
	public void onConnect(SocketChannel channel) {
		Socket socket=channel.socket();
		InetSocketAddress address=new InetSocketAddress(socket.getInetAddress(),socket.getPort());
		
		if(packetValidator.checkBanned(socket.getInetAddress())) {
			/* If address is banned, just close connection */
			try {
				channel.close();
			} catch (IOException e) {
				/* I don't think I want to listen to complains... */
				logger.info(e);
			}
		} else {
			sockets.put(address, channel);
		}
	}

	/** Removes the channel from the map when the client disconnect or is disconnected */
	public void onDisconnect(SocketChannel channel) {
		Socket socket=channel.socket();
		InetSocketAddress address=new InetSocketAddress(socket.getInetAddress(),socket.getPort());
		
		sockets.remove(address);
	}


	public void onData(NioServer server, SocketChannel channel, byte[] data, int count) {
		byte[] dataCopy = new byte[count];
		System.arraycopy(data, 0, dataCopy, 0, count);
		try {
			queue.put(new DataEvent(channel, dataCopy));
		} catch (InterruptedException e) {
			/* This is never going to happen */
		}
	}

	/**
	 * This method add a message to be delivered to the client the message
	 * is pointed to.
	 *
	 * @param msg the message to be delivered.
	 * @throws IOException 
	 */
	public void sendMessage(Message msg) {
		SocketChannel channel = null;
		synchronized (sockets) {
			channel = sockets.get(msg.getAddress());
		}		
		
		try {
			byte[] data = encoder.encode(msg);
			server.send(channel, data);
		} catch (IOException e) {
			e.printStackTrace();
			/** I am not interested in the exception. NioServer will detect this and close connection  */
		}
	}

	public void disconnectClient(InetSocketAddress address) {
		synchronized (sockets) {
			try {
			SocketChannel channel=sockets.remove(address);
			server.close(channel);
			} catch (Exception e) {
				e.printStackTrace();
			}
			
		}
		
	}

	public boolean isStillRunning() {
		return keepRunning;
	}
	
	@Override
	public void run() {
		try {
			while(keepRunning) {
				DataEvent event=queue.take();

				Socket socket=event.channel.socket();
				InetSocketAddress address=new InetSocketAddress(socket.getInetAddress(),socket.getPort());

				try {
					Message msg = decoder.decode(address, event.data);
					System.out.println("MESSAGE: "+msg);
					messages.add(msg);				
				} catch (InvalidVersionException e) {
					stats.add("Message invalid version", 1);
					System.out.println("INVALID");
					MessageS2CInvalidMessage invMsg = new MessageS2CInvalidMessage(address, "Invalid client version: Update client");
					sendMessage(invMsg);
				} catch (IOException e) {
					/* We don't care */
				}
			}			
		} catch (InterruptedException e) {
			keepRunning=false;
		}
		
		isFinished=true;
	}
	
	public static void main(String[] args) throws IOException, InterruptedException {
		new Thread() {
			public void run() {
				try {
					NIONetworkServerManager netman=new NIONetworkServerManager();
					netman.start();
					System.out.println("Started");

					Message msg=netman.getMessage();
					System.out.println("SERVER: "+msg);
					netman.sendMessage(msg);
					
					Thread.sleep(2000);

					netman.finish();
					System.out.println("Finished");
				} catch(Exception e) {
					e.printStackTrace();
				}
			}			
		}.start();

		new Thread() {
			public void run() {
				try {
					TCPThreadedNetworkClientManager clientman=new TCPThreadedNetworkClientManager("localhost",NetConst.marauroa_PORT);

					RPAction action=new RPAction();
					action.put("test",4);
					action.put("hola","caca");
					
					Message msg=new MessageC2SAction(null,action);
					clientman.addMessage(msg);
					
					Message rpl=null;
					while(rpl==null) {
						rpl=clientman.getMessage(1000);
					}
					
					System.out.println("CLIENT: "+rpl);
					
					System.exit(0);
				} catch(Exception e) {
					e.printStackTrace();
				}
			}			
		}.start();
	}
	
	
	
}