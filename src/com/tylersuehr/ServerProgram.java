/*
 * Copyright 2018 Tyler.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tylersuehr;

import com.sun.istack.internal.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Defines a multi-threaded server.
 * 
 * <b>Instantiation</b>
 * This is a singleton and a valid instance of this object can be accessed
 * via {@link #getInstance}.
 * 
 * <b>Threading</b>
 * This server will utilize a thread-pool to spawn threads to handle
 * client connections. This will allow us to reuse threads and will be
 * particular helpful if clients rapidly connect/disconnect.
 * 
 * The thread-pool will be managed by the Java {@link ThreadPoolExecutor}
 * class, and will be lazily loaded only when needed. This is a great
 * implementation of a thread-pool in conjunction with the Java 
 * {@link Executor}, so there's no need to reinvent the wheel.
 * 
 * <b>State</b>
 * This server is state-full (can be started/stopped) and contains various 
 * properties that are managed by flags to ensure optimal memory utilization.
 * 
 * <b>Basic Flow</b>
 * (1) Sends message to client indicating whether to continue or not.
 * (2) If continuing, services client requests on a new thread.
 * (3) If client 'quit', then disconnects from socket and thread 
 *     goes back into the pool.
 * 
 * @author Tyler Suehr
 * @author Win Ton
 * @author Steven Weber
 * @author David Wong
 */
public final class ServerProgram {
    /* Flag indicating the server has been started */
    private static final byte FLAG_STARTED = (byte)1;
    /* Flag indicating the server is currently running */
    private static final byte FLAG_RUNNING = (byte)2;
    
    /* Stores reference singleton instance in main mem */
    private static volatile ServerProgram instance;
    /* Stores reference to thread-pool executor */
    private static ThreadPoolExecutor sExecutor;
    /* Stores thread-safe active client count */
    private static volatile AtomicInteger sClientCount;
    /* Stores server flags in main mem */
    private volatile byte mFlags = 0;
    /* Stores server port number */
    private int mPort;
    
    /* Stores reference to server's socket so it can be started/stopped */
    private ServerSocket mServerSocket;
    /* Stores reference to request processor */
    private IRequestProcessor mProcessor;
    
    
    private ServerProgram() {
        mProcessor = new EchoRequestProcessor();
    }
    
    public static ServerProgram getInstance() {
        if (instance == null) {
            synchronized(ServerProgram.class) {
                if (instance == null) {
                    instance = new ServerProgram();
                }
            }
        }
        return instance;
    }
    
    /**
     * Starts the server on a given port and begins accepting 
     * client connections to it.
     * 
     * @param port to connect to
     */
    public void start(final int port) {
        if ((mFlags&FLAG_STARTED) == FLAG_STARTED) {
            throw new IllegalStateException("Server already started!");
        }
        mPort = port;
//        startHandlingClientRequests();

        // Reset active client connection count
        sClientCount = new AtomicInteger(0);
        try {
            // Instantiate the server's socket. Will be used by this
            // class to stop the server if #stop() is called.
            mServerSocket = new ServerSocket(mPort);

            // Update, is started and should be running at this point.
            mFlags = FLAG_STARTED|FLAG_RUNNING;
            
            awaitClientConnections();
        } catch (IOException ex) {
            throw new Error(ex);
        }
    }
    
    /**
     * Attempt to suspend server from accepting client connections.
     */
    public void pauseClientConnections() {
        mFlags |= ~FLAG_RUNNING;
    }
    
    /**
     * Attempt to resume server accepting client connections.
     */
    public void resumeClientConnections() {
        if ((mFlags&FLAG_RUNNING) != FLAG_RUNNING) {
            mFlags |= FLAG_RUNNING;
            awaitClientConnections();
        }
    }
    
    /**
     * Determines if the server has been started yet.
     * @return True if server started
     */
    public boolean isStarted() {
        return (mFlags&FLAG_STARTED) == FLAG_STARTED;
    }
    
    /**
     * Determines if the server is processing client connections.
     * @return True if processing client connections
     */
    public boolean isRunning() {
        return (mFlags&FLAG_RUNNING) == FLAG_RUNNING;
    }
    
    public IRequestProcessor getRequestProcessor() {
        return mProcessor;
    }
    
    public void setRequestProcessor(final IRequestProcessor processor) {
        mProcessor = processor;
    }
    
    @Deprecated
    private void startHandlingClientRequests() {
        try {
            // Set the active client count to 0
            sClientCount = new AtomicInteger(0);
            
            // Instantiate the server's socket. Will be used by this
            // class to stop the server if #stop() is called.
            mServerSocket = new ServerSocket(mPort);
            
            // Update the flags, the server is started and should
            // be running at this point.
            mFlags = FLAG_STARTED|FLAG_RUNNING;
            
            // While running, loop and handle client connections
            System.out.println("Waiting for client connection...");
            while ((mFlags&FLAG_RUNNING) == FLAG_RUNNING) {
                final Socket clientSocket = mServerSocket.accept();
                final ThreadPoolExecutor executor = getExecutor();
                
                // If our server is handling too many clients, then just
                // reject new ones by sending a pretty message.
                if (sClientCount.get() >= executor.getMaximumPoolSize()) {
                    rejectClient(clientSocket);
                } else {
                    sClientCount.incrementAndGet();
                    executor.execute(new ServerWorker(clientSocket, mProcessor));
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            
            // Update flags for serve state
            mFlags &= ~FLAG_STARTED;
            mFlags &= ~FLAG_RUNNING;
        }
    }
    
    /**
     * Loops while server is in running state to handle accepting
     * client connection requests.
     */
    private void awaitClientConnections() {
        System.out.println("Waiting for client connection...");
        
        // Loop while server is in the running state
        while ((mFlags&FLAG_RUNNING) == FLAG_RUNNING) {
            try {
                // Block while waiting for a client to connect
                final Socket clientSocket = mServerSocket.accept();
                final ThreadPoolExecutor executor = getExecutor();
                
                // Reject client if we're servicing too many clients
                if (sClientCount.get() >= executor.getMaximumPoolSize()) {
                    rejectClient(clientSocket);
                } else {
                    sClientCount.incrementAndGet();
                    executor.execute(new ServerWorker(clientSocket, mProcessor));
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
    
    /**
     * Sends a pretty message (with important "ERR") to client if server
     * cannot handle its requests and closes the socket.
     * 
     * @param socket of client
     */
    private void rejectClient(final Socket socket) {
        try (final PrintWriter out = new PrintWriter(socket.getOutputStream())) {
            // Tell client we're busy right now
            //
            // Importantly, we're sending "ERR" to let client know
            // not to wait for more server output and to disconnect.
            out.println("ERR: Server is handling too many clients, please connect later!");
            out.flush();
            
            // Close the socket
            socket.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    /**
     * Lazily loads the thread-pool executor with standard properties.
     * @return {@link ThreadPoolExecutor}
     */
    private static ThreadPoolExecutor getExecutor() {
        if (sExecutor == null) {
            final int SIZE = 2;
            final int MAX = 4;
            final int TIMEOUT = 30;
            sExecutor = new ThreadPoolExecutor(SIZE, MAX, TIMEOUT, 
                    TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX));
        }
        return sExecutor;
    }

    
    /**
     * Internal implementation of {@link Runnable} that services requests
     * from a single connected client.
     */
    private static final class ServerWorker implements Runnable {
        private final WeakReference<Socket> mClientSocketRef;
        private final IRequestProcessor mProcessor;
        
        ServerWorker(final Socket clientSocket, 
                @Nullable final IRequestProcessor processor) {
            mClientSocketRef = new WeakReference<>(clientSocket);
            mProcessor = processor;
        }
        
        @Override
        public void run() {
            // Get the client socket from the weak ref
            final Socket clientSocket = mClientSocketRef.get();
            if (clientSocket == null) { return; }
            System.out.println("Client connected on " + Thread.currentThread());
            
            // Open an auto-closeable writer and reader for the client
            try (final PrintWriter out = new PrintWriter(clientSocket.getOutputStream());
                 final BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                // Send success message to client
                out.println("SUCC");
                out.flush();
                
                // Handle each line of the client's request
                for (String line; (line = in.readLine()) != null;) {
                    // Echo that request was received
                    System.out.println("Client " + Thread.currentThread() + "): " + line);
                    out.println("Processing request...");
                    out.flush();
                    
                    // Check that client is still available
                    if (line.equals("quit")) {
                        break;
                    }
                    
                    // Process request and respond to client :D
                    out.println(mProcessor != null
                            ? mProcessor.onProcessRequest(line)
                            : "Request could not be processed at this time :(");
                    out.println();
                    out.flush();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            } finally {
                // Ensure the client socket gets closed
                try {
                    clientSocket.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
            
            System.out.println("Client connection terminated on " 
                    + Thread.currentThread());
            
            // Make sure we decrement the active client count!
            sClientCount.decrementAndGet();
        }
    }
    
    
    /**
     * Decouples implementation for processing client requests.
     * 
     * This will be used by each client worker to handle requests
     * sent by the client to the server.
     * 
     * This is ideal because we may want to change the behavior of
     * how the server processes client requests at various times.
     */
    public interface IRequestProcessor {
        String onProcessRequest(String request);
    }
    
    /**
     * Internal implementation of {@link IRequestProcessor} that affords
     * logic to simply echo a client's request.
     */
    private static class EchoRequestProcessor implements IRequestProcessor {
        @Override
        public String onProcessRequest(String request) {
//            // Attempt to recognize request
//            request = request.toLowerCase();
//            if (request.contains("hello") 
//                    || request.contains("hi")
//                    || request.contains("hey")) {
//                return "Well hello there!";
//            }
            
            // Echo request if not recognized
            return "(Echo) " + request;
        }
    }
    
    
    public static void main(String[] args) {
        ServerProgram.getInstance().start(8080);
    }
}
