/*
 * Copyright 2018 Group 5.
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
package edu.psu.ist411;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Defines a simple client for the multi-threaded server demonstration.
 *
 * <b>Instantiation</b>
 * This is a singleton and a valid instance of this object can be accessed
 * via {@link #getInstance}.
 * 
 * <b>Basic Flow</b>
 * (1) Connects to server.
 * (2) Awaits response (could be "ERR", so abort).
 * (3) Inquire input from user.
 * (4) Send input to server.
 * (5) Await server's response to input.
 * 
 * @author Tyler Suehr
 * @author Win Ton
 * @author Steven Weber
 * @author David Wong
 */
public final class ClientProgram {
    /* Stores reference to singleton instance in main mem */
    private static volatile ClientProgram instance;
    
    
    private ClientProgram() {}
    
    public static ClientProgram getInstance() {
        if (instance == null) {
            synchronized (ClientProgram.class) {
                if (instance == null) {
                    instance = new ClientProgram();
                }
            }
        }
        return instance;
    }
   
    /**
     * Attempts to connect to the server via given host and port.
     * This allows the user of the client program to type in requests
     * to the server to echo (for this project's requirements).
     * 
     * @param host of the server
     * @param port number of the server
     */
    public void connect(final String host, final int port) {
        // Connect to our server program using auto-closeable resources
        try (final Socket socket = new Socket(host, port);
                final PrintWriter out = new PrintWriter(socket.getOutputStream());
                final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                final BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in))) {
            // If our server initially sends error "ERR" then disconnect
            String resp = in.readLine();
            if (resp != null && resp.contains("ERR")) {
                System.out.println(resp);
                return;
            }
            
            // Loop until user of client program quits
            String stdInput;
            while (true) {
                // Inquire input from user of client program
                System.out.print("Enter text: ");
                stdInput = stdIn.readLine();
                if (stdInput != null && stdInput.equals("quit")) {
                    break;
                }
                
                // Send client input to server
                out.println(stdInput);
                out.flush();
                
                // Get responses from server until it sends an empty str
                for (;(resp = in.readLine()) != null && !resp.equals("");) {
                    System.out.println("Server: " + resp);
                }
            }
        } catch (IOException ex) {
            throw new Error(ex);
        }
    }
    
    
    public static void main(String[] args) {
        try {
            final InetAddress address = InetAddress.getLocalHost();
            ClientProgram.getInstance().connect(address.getHostName(), 8080);
        } catch (IOException ex) {
            throw new Error(ex);
        }
    }
}
