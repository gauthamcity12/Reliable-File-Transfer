import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.io.*;
import java.util.*;

public class client {


	private Connection connection; //HashMap<Integer, Connection> connections = new HashMap<>(5);
	private static final int TIMEOUT = 3000;
	private static final int MAXTRIES = 5;
	private static Random rand = new Random();
	private static boolean connectFlag = false;

	public static void main(String[] args) {
		if (args.length != 4) {
			System.out.println("Incorrect number of arguments.  Please enter arguments as\nFxA-client <src port> <dst IP> <dst port>");
			System.exit(1);
		}

		client clientUser = new client();
		
		int ownPort = -1;
		InetAddress serverIP = null;
		int serverPort = -1;
		DatagramSocket socket = null;

		try {
			ownPort = Integer.parseInt(args[1]);
		} catch (NumberFormatException n) {
			System.out.println("Port number must be an int.");
			System.exit(1);
		}
		
		try {
			serverIP = InetAddress.getByName(args[2]);
		} catch (UnknownHostException u) {
			System.out.println("Destination IP address was improperly formatted.");
			System.exit(1);
		}

		try {
			serverPort = Integer.parseInt(args[3]);
		} catch (NumberFormatException n2) {
			System.out.println("Port number must be an int.");
			System.exit(1);
		}
		
		try {
			socket = new DatagramSocket(ownPort);		//NEED TO INCLUDE IP ADDRESS???  
		} catch (SocketException s) {
			System.out.println("Could not create socket.");
			System.exit(1);
		}
		
		//InputStreamReader algorithm from http://stackoverflow.com/questions/7872846/how-to-read-from-standard-input-non-blocking
		
		while (true) {
			InputStreamReader inStream = new InputStreamReader(System.in);	//create stream for reading in commands
			BufferedReader reader = new BufferedReader(inStream);
			boolean ready = false;
			try {
				ready = reader.ready();
			} catch (Exception e) {
				ready = false;
			}
			if (ready) {
				try {
					String next = reader.readLine();
					if ((next.length() >= 7) && (next.substring(0, 7)).equals("connect")) {
						if (clientUser.connect(serverIP, serverPort, socket)) {
							System.out.println("Successfully connected to server application.");
							connectFlag = true;
						} else {
							System.out.println("Could not connect to server application. Please try again.");
							System.exit(1);
						}
						while (connectFlag) {
							boolean insideReady = false;
							try {
								insideReady = reader.ready();
							} catch (Exception e) {
								insideReady = false;
							}
							if (insideReady) {
								try {
									next = reader.readLine();
									if ((next.length() >= 5) && (next.substring(0, 4)).equals("get ")) {
										String file = next.substring(4);
										if (clientUser.send((byte) 0, file, socket)) {
											System.out.println("GET functioned properly");
										} else {
											System.out.println("Failed to get");
										}
									} else if ((next.length() >= 6) && (next.substring(0, 5)).equals("post ")) {
										String file = next.substring(5);
										if (clientUser.send((byte) 1, file, socket)) {
											System.out.println("POST functioned properly");
										} else {
											System.out.println("Failed to post");
										}
									} else if ((next.length() >= 10) && (next.substring(0, 10)).equals("disconnect")) {
										if (clientUser.close(clientUser.connection.getSessionID(), socket)) {
											System.out.println("Successfully close with server application.");
											connectFlag = false;
										} else {
											System.out.println("Could not close with server application.");
										}
									} else {
										System.out.println("You have not entered a valid command.  Options are\nget <file>\npost <file>\nterminate.");
									}
								} catch (Exception e) {}
							} else {
								if (!clientUser.receive(socket)) {
									connectFlag = false;
								}
							}
						}
					} else {
						System.out.println("You have not established a connection.\nPlease type the \"connect\" command to do so.");
					}
				} catch (Exception e) {}
			}
		}
	}

	
	
	public client() {
		//do nothing;
	}
	
	//CHECK SEQ/ACK NUMS and MD5
	public boolean connect(InetAddress address, int port, DatagramSocket socket) {
		int session1 = rand.nextInt();
		Packet SYNDataPacket = new Packet(0, session1, 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 0, 32000, new byte[0], 0);
		DatagramPacket SYNpacket = new DatagramPacket(SYNDataPacket.toArray(), SYNDataPacket.toArray().length, address, port);
		DatagramPacket SYNACKrcvPacket = new DatagramPacket(new byte[SYNDataPacket.toArray().length], SYNDataPacket.toArray().length);
		
		boolean receivedResponse = trySend(socket, SYNpacket, SYNACKrcvPacket, address);

		if ((receivedResponse) && (verifyAck(SYNACKrcvPacket).getAckNum() == session1) && (checkHash(SYNACKrcvPacket))) {
			byte[] rcvData = SYNACKrcvPacket.getData();
			byte checkVal = (byte) 1;
			if ((rcvData[15] == checkVal) && (rcvData[16] == checkVal)) { // if SYNACK is received
				int session2 = verifyAck(SYNACKrcvPacket).getSeqNum();
				Packet ACKDataPacket = new Packet(0, session1 + 1, session2, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, 32000, new byte[0], 0);
				DatagramPacket ACKpacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, address, port);
				DatagramPacket ACKrcvPacket = new DatagramPacket(new byte[ACKDataPacket.toArray().length], ACKDataPacket.toArray().length);
				
				receivedResponse = trySend(socket, ACKpacket, ACKrcvPacket, address);
				
				if ((receivedResponse) && (verifyAck(ACKrcvPacket).getAckNum() == (session1 + 1)) && (checkHash(ACKrcvPacket))) { // if ACK is received
					byte[] rcvData2 = ACKrcvPacket.getData();
					checkVal = (byte) 1;
					if (rcvData2[16] == checkVal) {
						this.connection = new Connection(session1 + session2, session1 + 2, session2, address, port);
						System.out.println("Connection successful!");
						return true;
					}
				}
			}
		}
		System.out.println("Connection failed.");
		return false;
	}

	private static Packet verifyAck(DatagramPacket pack) {
		return new Packet(pack.getData());
	}

	private static boolean checkHash(DatagramPacket pack) {
		Packet tempPack = verifyAck(pack);
		byte[] rcvHash = tempPack.getHash();
		
		MessageDigest hash;
		try {
			hash = MessageDigest.getInstance("MD5");
		} catch (java.security.NoSuchAlgorithmException e) {
			return false;
		}
		ByteBuffer temp = ByteBuffer.allocate(25);
		temp.putInt(tempPack.getSessionID());
		temp.putInt(tempPack.getSeqNum());
		temp.putInt(tempPack.getAckNum());
		temp.put(tempPack.getGET());
		temp.put(tempPack.getPOST());
		temp.put(tempPack.getFIN());
		temp.put(tempPack.getSYN());
		temp.put(tempPack.getACK());
		temp.putInt(tempPack.getRcvWind());
		temp.putInt(tempPack.getDataSize());
		byte[] anotherTemp = temp.array();
		//Taken from http://stackoverflow.com/questions/5513152/easy-way-to-concatenate-two-byte-arrays
		byte[] aboutToHash = new byte[anotherTemp.length + tempPack.getDataSize()];
		System.arraycopy(anotherTemp, 0, aboutToHash, 0, anotherTemp.length);
		System.arraycopy(tempPack.getData(), 0, aboutToHash, anotherTemp.length, tempPack.getDataSize());
		byte[] checkHash = hash.digest(aboutToHash);
		return Arrays.equals(rcvHash, checkHash);
		//return true;
	}
	
	private static boolean trySend(DatagramSocket socket, DatagramPacket sendP, DatagramPacket rcvP, InetAddress address) {
		boolean receivedResponse = false;
		int tries = 0;
		do {
			try {
				socket.setSoTimeout(TIMEOUT);
				socket.send(sendP);
				socket.receive(rcvP);
				if (!rcvP.getAddress().equals(address)) { //Check source for received packet
					throw new IOException("Received packet was from unknown source");
				}
				receivedResponse = true;
			} catch (InterruptedIOException e) {
				tries += 1;
				System.out.println("Timed out, " + (MAXTRIES - tries) + " more tries.");
			} catch (Exception f) {
				return false;
			}
		} while ((!receivedResponse) && (tries < MAXTRIES));
		return receivedResponse;
	}
	
	private static boolean trySend(DatagramSocket socket, DatagramPacket sendP) {
		boolean receivedResponse = false;
		int tries = 0;
		do {
			try {
				socket.setSoTimeout(TIMEOUT);
				socket.send(sendP);
				receivedResponse = true;
			} catch (InterruptedIOException e) {
				tries += 1;
				System.out.println("Timed out, " + (MAXTRIES - tries) + " more tries.");
			} catch (Exception f) {
				System.out.println(f);
				return false;
			}
		} while ((!receivedResponse) && (tries < MAXTRIES));
		return receivedResponse;
	}
	
	private static boolean tryReceive(DatagramSocket socket, DatagramPacket rcvP, InetAddress address) {
		try {
			socket.receive(rcvP);
			if (!rcvP.getAddress().equals(address)) { //Check source for received packet
				throw new IOException("Received packet was from unknown source");
			}
			return true;
		} catch (Exception f) {
			return tryReceive(socket, rcvP, address);
		}
	}
	
	private static boolean tryInitialReceive(DatagramSocket socket, DatagramPacket rcvP, InetAddress address) {
		try {
			socket.setSoTimeout(100);
			socket.receive(rcvP);
			if (!rcvP.getAddress().equals(address)) { //Check source for received packet
				throw new IOException("Received packet was from unknown source");
			}
			return true;
		} catch (InterruptedIOException e) {
			return false;
		} catch (Exception f) {
			return tryInitialReceive(socket, rcvP, address);
		}
	}

	private static boolean trySend(DatagramSocket socket, DatagramPacket sendP, InetAddress address, Connection connect, String filename) {
		DatagramPacket rcvP = new DatagramPacket(new byte[Packet.MAXPACKETSIZE], Packet.MAXPACKETSIZE);
		int lastAck = 0;
		int lastSeq = 0;
		boolean receivedResponse = false;
		int tries = 0;
		try {
			//DataOutputStream algorithm from http://stackoverflow.com/questions/12977290/write-and-read-multiple-byte-in-file-with-java
			DataOutputStream dataOutStream = new DataOutputStream(new FileOutputStream(new File(filename)));
			do {
				try {
					socket.setSoTimeout(TIMEOUT);
					trySend(socket, sendP, rcvP, address); // Sending the GET packet
					if (!rcvP.getAddress().equals(address)) { //Check source for received packet
						throw new IOException("Received packet was from unknown source");
					}
					if ((verifyAck(rcvP).getACK() != 1) || (verifyAck(rcvP).getPOST() != 1) || (!checkHash(rcvP))) { // send GET request again
						if ((verifyAck(rcvP).getGET() == 0) && (verifyAck(rcvP).getPOST() == 0) && (verifyAck(rcvP).getSYN() == 0) && (verifyAck(rcvP).getFIN() == 0) && (verifyAck(rcvP).getACK() == 0) && (checkHash(rcvP))) {
							System.out.println("File does not exist on the server.");
							return false;	//file does not exist
						}
						while ((verifyAck(rcvP).getACK() != 1) || (verifyAck(rcvP).getPOST() != 1) || (!checkHash(rcvP))) {
							if ((verifyAck(rcvP).getGET() == 0) && (verifyAck(rcvP).getPOST() == 0) && (verifyAck(rcvP).getSYN() == 0) && (verifyAck(rcvP).getFIN() == 0) && (verifyAck(rcvP).getACK() == 0) && (checkHash(rcvP))) {
								System.out.println("File does not exist on the server.");
								return false;	//file does not exist
							}
							trySend(socket, sendP, rcvP, address);
						}
					}
					int dsz = verifyAck(rcvP).getDataSize();
					byte[] data = new byte[dsz];
					System.arraycopy(verifyAck(rcvP).getData(), 0, data, 0, dsz);
					connect.addData(data);			//CHECK ON LENGTH!!!!!!!!!!!!???????????
					writeDataToFile(filename, connect.getData(), connect, dataOutStream);
					lastAck = verifyAck(rcvP).getSeqNum(); // seq # of the first data packet
					lastSeq = verifyAck(rcvP).getAckNum();
					connect.setAckNum(verifyAck(rcvP).getSeqNum());
					connect.setSeqNum(verifyAck(rcvP).getAckNum());
					
					Packet ACKDataPacket = new Packet(verifyAck(sendP).getSessionID(), lastSeq++, lastAck, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, connect.getRcvWind(), new byte[0], 0);
					DatagramPacket ACKpacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, address, connect.getPort());
					connect.setSeqNum(connect.getSeqNum()+1);
					while (!trySend(socket, ACKpacket)) {}			//RISK OF INFINITE LOOP!!!!!!!!!!!!
					
					receivedResponse = true;
				} catch (InterruptedIOException e) {
					tries += 1;
					System.out.println("Timed out, " + (MAXTRIES - tries) + " more tries.");
				} catch (Exception f) {
					return false;
				}
			} while ((!receivedResponse) && (tries < MAXTRIES));
			if (tries >= MAXTRIES) return false;
			Packet rcv = null;
			do {
				if (tryReceive(socket, rcvP, address)) {
					rcv = verifyAck(rcvP);
					if ((rcv.getGET() == 0) && (rcv.getPOST() == 0) && (rcv.getSYN() == 0) && (rcv.getFIN() == 0) && (rcv.getACK() == 0) && (checkHash(rcvP))) {
						Packet ACKFDataPacket = new Packet(verifyAck(sendP).getSessionID(), lastSeq++, (lastAck = rcv.getSeqNum()), (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, connect.getRcvWind(), new byte[0], 0);
						DatagramPacket ACKFpacket = new DatagramPacket(ACKFDataPacket.toArray(), ACKFDataPacket.toArray().length, address, connect.getPort());
						while (!trySend(socket, ACKFpacket)) {}			//RISK OF INFINITE LOOP!!!!!!!!!!!!!!!!!!!!!
						connect.setAckNum(lastAck);				//CHECK ON SETTING THIS AT OTHER RETURNS
						connect.setSeqNum(lastSeq + 1);
						return true;
					} else {
						int dsz = verifyAck(rcvP).getDataSize();
						byte[] data = new byte[dsz];
						System.arraycopy(verifyAck(rcvP).getData(), 0, data, 0, dsz);
						DatagramPacket ACKPacket = null;
						if ((verifyAck(rcvP).getACK() != 1) || (verifyAck(rcvP).getPOST() != 1) || (verifyAck(rcvP).getSeqNum() != lastAck + 1) || (!checkHash(rcvP))) { // re-ACK 
							Packet ACKDataPacket = new Packet(verifyAck(rcvP).getSessionID(), lastSeq, lastAck, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, verifyAck(sendP).getRcvWind(), new byte[0], 0);
							ACKPacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, address, connect.getPort());
							while ((verifyAck(rcvP).getACK() != 1) || (verifyAck(rcvP).getPOST() != 1) || (verifyAck(rcvP).getSeqNum() != lastAck + 1)) {
								trySend(socket, ACKPacket, rcvP, address);
							}
						}
						connect.addData(data);			//CHECK ON LENGTH!!!!!!!!!!!!???????????
						writeDataToFile(filename, connect.getData(), connect, dataOutStream);
						lastAck = verifyAck(rcvP).getSeqNum();
						lastSeq = verifyAck(rcvP).getAckNum(); //verifyAck(ACKPacket).getSeqNum(); // DOUBLE CHECK THIS // last SEQ # that was sent
						connect.setAckNum(verifyAck(rcvP).getSeqNum());
						connect.setSeqNum(verifyAck(rcvP).getAckNum());
						
						Packet ACKDataPacket = new Packet(verifyAck(sendP).getSessionID(), lastSeq++, lastAck, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, connect.getRcvWind(), new byte[0], 0);
						DatagramPacket ACKpacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, address, connect.getPort());
						connect.setSeqNum(connect.getSeqNum()+1);
						while (!trySend(socket, ACKpacket)) {} //RISK OF INFINITE LOOP
					}
				} else {
					return false; //will never happen
				}
			} while (true);
		} catch (Exception e) { //should never happen
			System.out.println("Error making file: " + e);
			return false;
		}
	}
	
	public boolean send(byte flag, String filenameArg, DatagramSocket socket) {
		if (flag == 0) { //GET request
			byte[] data = filenameArg.getBytes();
			if (data.length > Packet.MAXDATASIZE) {
				System.out.println("File name is too long.");	//this would be absurd for a file name
			}
			Packet sendDataPacket = new Packet(this.connection.getSessionID(), this.connection.getSeqNum(), 47, (byte) 1, (byte) 0, (byte) 0, (byte) 0, (byte) 0, connection.getRcvWind(), data, data.length);
			DatagramPacket sendPacket = new DatagramPacket(sendDataPacket.toArray(), sendDataPacket.toArray().length, connection.getAddress(), connection.getPort());
			this.connection.setSeqNum(this.connection.getSeqNum() + 1); //should we increment here
			
			if (trySend(socket, sendPacket, this.connection.getAddress(), this.connection, filenameArg)) { //DAMAGE LINE
				return true;
			}
			return false;
		} else { //POST data
			byte[] data = filenameArg.getBytes();
			if (data.length > Packet.MAXDATASIZE) {
				System.out.println("File name is too long.");	//this would be absurd for a file name
			}
			Packet sendDataPacket = new Packet(this.connection.getSessionID(), this.connection.getSeqNum(), 47, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 0, connection.getRcvWind(), data, data.length);
			ArrayList<Packet> toSend = retrieveFile(filenameArg, connection);
			if (toSend.size() < 2) {
				System.out.println("File does not exist on your host.");
				return true;
			}
			toSend.add(0, sendDataPacket);
			while (!toSend.isEmpty()) {
				Packet temp = toSend.remove(0);
				temp.setSeqNum(connection.getSeqNum());
				connection.setSeqNum(connection.getSeqNum() + 1);
				temp.setRcvWind(connection.getRcvWind());
				temp.recalculateHash();
				DatagramPacket packetToSend = new DatagramPacket(temp.toArray(), temp.toArray().length, connection.getAddress(), connection.getPort());
				DatagramPacket genericRcvPacket = new DatagramPacket(new byte[Packet.MAXPACKETSIZE], Packet.MAXPACKETSIZE);
				while ((!trySend(socket, packetToSend, genericRcvPacket, connection.getAddress())) || (verifyAck(genericRcvPacket).getACK() != (byte) 1) || (verifyAck(genericRcvPacket).getAckNum() != temp.getSeqNum()) || (!checkHash(genericRcvPacket))) {}
			}
			return true;
		}
	}
	
	private ArrayList<Packet> retrieveFile(String filename, Connection connection) { //seq & ack numbers & rcvWind are not set and should be before they are sent
		ArrayList<Packet> packetStream = new ArrayList<>(); //will be returned
		
		Packet endOfFilePacket = new Packet(connection.getSessionID(), 0, 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, connection.getRcvWind(), new byte[0], 0);
		
		File fnameFile = new File(filename);
		boolean loopflag = true;
		byte[] fileData = new byte[0];
		int sz = 0;
		if (Files.exists(fnameFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
			while (loopflag) {
				try {
					sz = Files.readAllBytes(fnameFile.toPath()).length;
					fileData = new byte[sz];
					fileData = Files.readAllBytes(fnameFile.toPath());
					loopflag = false;
				} catch (Exception e) {}			//RISK OF INFINITE LOOP!!!!!!!!!!!!!!
			}
			int intFileIndex = 0;
			int maxDataPerPacket = Packet.MAXDATASIZE;
			int numPackets = (sz / maxDataPerPacket) + 1;
			for (int i = 0; i < numPackets; i++) {
				byte[] temp = new byte[Math.min(maxDataPerPacket, sz - intFileIndex)];
				System.arraycopy(fileData, intFileIndex, temp, 0, temp.length);
				intFileIndex += temp.length;
				Packet tempPacket = new Packet(connection.getSessionID(), 0, 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 0, connection.getRcvWind(), temp, temp.length);
				packetStream.add(tempPacket);
			}
			packetStream.add(endOfFilePacket);
			return packetStream;
		} else {
			packetStream.add(endOfFilePacket);
			return packetStream;
		}
	}
	
	private ArrayList<Packet> retrieveFile(Packet rcvPacket, Connection connection) { //seq & ack numbers & rcvWind are not set and should be before they are sent
		ArrayList<Packet> packetStream = new ArrayList<>(); //will be returned
		
		Packet endOfFilePacket = new Packet(rcvPacket.getSessionID(), 0, 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, connection.getRcvWind(), new byte[0], 0);
		
		int dsz = rcvPacket.getDataSize();
		byte[] fnameArray = new byte[dsz];
		System.arraycopy(rcvPacket.getData(), 0, fnameArray, 0, dsz);
		String filename = new String(fnameArray);
		File fnameFile = new File(filename);
		boolean loopflag = true;
		byte[] fileData = new byte[0];
		int sz = 0;
		if (Files.exists(fnameFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
			while (loopflag) {
				try {
					sz = Files.readAllBytes(fnameFile.toPath()).length;
					fileData = new byte[sz];
					fileData = Files.readAllBytes(fnameFile.toPath());
					loopflag = false;
				} catch (Exception e) {}			//RISK OF INFINITE LOOP!!!!!!!!!!!!!!
			}
			int intFileIndex = 0;
			int maxDataPerPacket = Packet.MAXDATASIZE;
			int numPackets = (sz / maxDataPerPacket) + 1;
			for (int i = 0; i < numPackets; i++) {
				byte[] temp = new byte[Math.min(maxDataPerPacket, sz - intFileIndex)];
				System.arraycopy(fileData, intFileIndex, temp, 0, temp.length);
				intFileIndex += temp.length;
				Packet tempPacket = new Packet(rcvPacket.getSessionID(), 0, 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 1, connection.getRcvWind(), temp, temp.length);
				packetStream.add(tempPacket);
			}
			packetStream.add(endOfFilePacket);
			return packetStream;
		} else {
			packetStream.add(endOfFilePacket);
			return packetStream;
		}
	}
	
	private static void writeDataToFile(String filename, byte[] dataToWrite, Connection connection, DataOutputStream dataOutStream) {
		boolean loopflag = true; //DAMAGE LINE
		while (loopflag) {
			try {
				dataOutStream.write(dataToWrite, 0, dataToWrite.length);
				loopflag = false;
			} catch (Exception e) {} 					//RISK OF INFINITE LOOP!!!
		}
	}
	
	public boolean receive(DatagramSocket socket){
		DatagramPacket genericRcvPacket = new DatagramPacket(new byte[Packet.MAXPACKETSIZE], Packet.MAXPACKETSIZE);
		if ((tryInitialReceive(socket, genericRcvPacket, connection.getAddress())) && (checkHash(genericRcvPacket))) {
			if (verifyAck(genericRcvPacket).getFIN() == (byte) 1) { //server initiated close
				if (closeReceive(socket, verifyAck(genericRcvPacket))) {
					connection = null;
					System.out.println("Connection closed.");
					return false;
				} else {
					System.out.println("Failed in attempt to handle server initiated close.");
					return true;
				}
			} else if (verifyAck(genericRcvPacket).getGET() == (byte) 1) {	//server sends GET request packet
				ArrayList<Packet> toSend = retrieveFile(verifyAck(genericRcvPacket), connection);
				while (!toSend.isEmpty()) {
					Packet temp = toSend.remove(0);
					temp.setSeqNum(connection.getSeqNum());
					connection.setSeqNum(connection.getSeqNum() + 1);
					connection.setAckNum(verifyAck(genericRcvPacket).getSeqNum());
					temp.setAckNum(connection.getAckNum());
					temp.setRcvWind(connection.getRcvWind());
					temp.recalculateHash();
					DatagramPacket packetToSend = new DatagramPacket(temp.toArray(), temp.toArray().length, connection.getAddress(), connection.getPort());
					genericRcvPacket = new DatagramPacket(new byte[Packet.MAXPACKETSIZE], Packet.MAXPACKETSIZE);
					while ((!trySend(socket, packetToSend, genericRcvPacket, connection.getAddress())) || (verifyAck(genericRcvPacket).getACK() != (byte) 1) || (verifyAck(genericRcvPacket).getAckNum() != temp.getSeqNum()) || (!checkHash(genericRcvPacket))) {}
				}
				return true;
			} else if (verifyAck(genericRcvPacket).getPOST() == (byte) 1) {	//server sends POST data packet
				int dsz = verifyAck(genericRcvPacket).getDataSize();
				byte[] fnameArray = new byte[dsz];
				System.arraycopy(verifyAck(genericRcvPacket).getData(), 0, fnameArray, 0, dsz);
				String filename = new String(fnameArray);
				Packet ACKDataPacket = new Packet(connection.getSessionID(), connection.getSeqNum(), verifyAck(genericRcvPacket).getSeqNum(), (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, connection.getRcvWind(), new byte[0], 0);
				DatagramPacket ACKPacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, connection.getAddress(), connection.getPort());
				connection.setSeqNum(connection.getSeqNum() + 1);
				connection.setAckNum(verifyAck(genericRcvPacket).getSeqNum());
				return downloadPostedFile(filename, ACKPacket, connection, socket);
			}
		}
		/*
		 * if received ACK											//SHOULD ACK PACKETS HAVE A SEQ NUM AND BE ADDED TO THE WINDOW???
		 * 		check ACK against expected number and the window	//SHOULD WE ACK AND RESPOND OR SIMPLY RESPOND TO THINGS LIKE GET???
		 * 		update window variables and window
		 * if received GET
		 * 		retrieve data
		 * 		send data back
		 * if received POST ***implement at the end***
		 * 		read in and store data
		 * 		send ACK
		 */
		return true;
	}

	private static boolean downloadPostedFile(String filename, DatagramPacket ACKPacket, Connection connection, DatagramSocket socket) {
		DatagramPacket genericRcvPacket = new DatagramPacket(new byte[Packet.MAXPACKETSIZE], Packet.MAXPACKETSIZE);
		while (!trySend(socket, ACKPacket, genericRcvPacket, connection.getAddress())) {} //POSSIBLE INFINITE LOOP
		Packet ACKDataPacket = null;
		try {
			//DataOutputStream algorithm from http://stackoverflow.com/questions/12977290/write-and-read-multiple-byte-in-file-with-java
			DataOutputStream dataOutStream = new DataOutputStream(new FileOutputStream(new File(filename)));
			boolean flag = true;
			while (flag) {
				if ((verifyAck(genericRcvPacket).getGET() == 0) && (verifyAck(genericRcvPacket).getPOST() == 0) && (verifyAck(genericRcvPacket).getSYN() == 0) && (verifyAck(genericRcvPacket).getFIN() == 0) && (verifyAck(genericRcvPacket).getACK() == 0) && (checkHash(genericRcvPacket))) {
					ACKDataPacket = new Packet(connection.getSessionID(), connection.getSeqNum(), verifyAck(genericRcvPacket).getSeqNum(), (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, connection.getRcvWind(), new byte[0], 0);
					ACKPacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, connection.getAddress(), connection.getPort());
					connection.setAckNum(verifyAck(genericRcvPacket).getSeqNum());
					connection.setSeqNum(connection.getSeqNum() + 1);
					while (!trySend(socket, ACKPacket)) {}	//RISK OF INFINITE LOOP
					flag = false;
					break;
				}
				int size = verifyAck(genericRcvPacket).getDataSize();
				byte[] data = new byte[size];
				System.arraycopy(verifyAck(genericRcvPacket).getData(), 0, data, 0, size);
				connection.addData(data);			//CHECK ON LENGTH!!!!!!!!!!!!???????????
				writeDataToFile(filename, connection.getData(), connection, dataOutStream);
				ACKDataPacket = new Packet(connection.getSessionID(), connection.getSeqNum(), verifyAck(genericRcvPacket).getSeqNum(), (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, connection.getRcvWind(), new byte[0], 0);
				ACKPacket = new DatagramPacket(ACKDataPacket.toArray(), ACKDataPacket.toArray().length, connection.getAddress(), connection.getPort());
				connection.setAckNum(verifyAck(genericRcvPacket).getSeqNum());
				connection.setSeqNum(connection.getSeqNum() + 1);
				while (!trySend(socket, ACKPacket, genericRcvPacket, connection.getAddress())) {} //POSSIBLE INFINITE LOOP
			}
			return true;
		} catch (Exception e) { //should never happen
			System.out.println("Trouble downloading file: " + e);
			return false;
		}
	}

	private boolean closeReceive(DatagramSocket socket, Packet serverACKPack) {
		Packet FINACKDataPacket = new Packet(serverACKPack.getSessionID(), connection.getSeqNum(), serverACKPack.getSeqNum(), (byte) 0, (byte) 0, (byte) 1, (byte) 0, (byte) 1, connection.getRcvWind(), new byte[0], 0);
		DatagramPacket FINACKpacket = new DatagramPacket(FINACKDataPacket.toArray(), FINACKDataPacket.toArray().length, connection.getAddress(), connection.getPort());
		DatagramPacket rcvPacket = new DatagramPacket(new byte[FINACKDataPacket.toArray().length], FINACKDataPacket.toArray().length);
		
		boolean receivedResponse = trySend(socket, FINACKpacket, rcvPacket, connection.getAddress());
		connection.setSeqNum(connection.getSeqNum() + 1);
		
		if ((receivedResponse) && (verifyAck(rcvPacket).getAckNum() == FINACKDataPacket.getSeqNum()) && (checkHash(rcvPacket))){
			if (verifyAck(rcvPacket).getACK() == (byte) 1) {
				return true;
			}
		}
		return false;
	}

	//CHECK SEQ/ACK NUMS and MD5
	public boolean close(int ID, DatagramSocket socket) {	
		InetAddress address = this.connection.getAddress();
		int port = this.connection.getPort();
		
		Packet FINDataPacket = new Packet(ID, this.connection.getSeqNum(), this.connection.getAckNum(), (byte) 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, this.connection.getRcvWind(), new byte[0], 0);
		DatagramPacket FINpacket = new DatagramPacket(FINDataPacket.toArray(), FINDataPacket.toArray().length, address, port);
		DatagramPacket rcvPacket = new DatagramPacket(new byte[FINDataPacket.toArray().length], FINDataPacket.toArray().length);
		
		boolean receivedResponse = trySend(socket, FINpacket, rcvPacket, address);
		connection.setSeqNum(connection.getSeqNum() + 1);
		
		if ((receivedResponse) && (verifyAck(rcvPacket).getAckNum() == connection.getSeqNum() - 1) && (checkHash(rcvPacket))) {
			byte[] rcvData = rcvPacket.getData();
			byte checkVal = (byte) 1;
			if (rcvData[16] == checkVal) {
				Packet ACKFinalPacket = new Packet(ID, this.connection.getSeqNum(), verifyAck(rcvPacket).getSeqNum(), (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, this.connection.getRcvWind(), new byte[0], 0);
				DatagramPacket ACKFPacket = new DatagramPacket(ACKFinalPacket.toArray(), ACKFinalPacket.toArray().length, address, port);
				connection.setSeqNum(connection.getSeqNum() + 1);
				connection.setAckNum(verifyAck(rcvPacket).getSeqNum());
				
				if (rcvData[14] == checkVal) {
					if (trySend(socket, ACKFPacket)) {
						this.connection.setAckNum(verifyAck(rcvPacket).getSeqNum());
						connection = null;
						return true;
					}
					System.out.println("Could not close connection.");
					return false;
				} else {
					rcvPacket = new DatagramPacket(new byte[FINDataPacket.toArray().length], FINDataPacket.toArray().length);
					try {
						socket.receive(rcvPacket);
						if (!rcvPacket.getAddress().equals(address)) { //Check source for received packet
							throw new IOException("Received packet was from unknown source");
						}
						ACKFinalPacket.setAckNum(verifyAck(rcvPacket).getSeqNum());
						connection.setAckNum(verifyAck(rcvPacket).getSeqNum());
						ACKFinalPacket.setRcvWind(connection.getRcvWind());
						ACKFinalPacket.recalculateHash();
						ACKFPacket = new DatagramPacket(ACKFinalPacket.toArray(), ACKFinalPacket.toArray().length, address, port);
					} catch (Exception f) {
						System.out.println("Could not close connection.");
						return false;
					}
					if (rcvData[14] == checkVal) {
						if (trySend(socket, ACKFPacket)) {
							this.connection.setAckNum(verifyAck(rcvPacket).getSeqNum());
							connection = null;
							return true;
						}
						System.out.println("Could not close connection.");
						return false;
					}
				}
			}
		}
		System.out.println("Could not close connection.");
		return false;
	}
}
