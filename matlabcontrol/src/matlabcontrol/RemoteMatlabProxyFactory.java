package matlabcontrol;

/*
 * Copyright (c) 2011, Joshua Kaplan
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *  - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *  - Neither the name of matlabcontrol nor the names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.IOException;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.ArrayList;

/**
 * Creates remote instances of {@link MatlabProxy}. Creating a proxy will launch MATLAB. Each proxy created will control
 * the session launched. This factory can be used to create any number of proxies.
 * 
 * @author <a href="mailto:jak2@cs.brown.edu">Joshua Kaplan</a>
 */
class RemoteMatlabProxyFactory implements ProxyCreator
{
    /**
     * A timer that periodically checks if the proxies are still connected.
     */
    private Timer _connectionTimer;
    
    /**
     * Manages {@link MatlabConnectionListener}s.
     */
    private final MatlabConnectionListenerManager _listenerManager = new MatlabConnectionListenerManager();
    
    /**
     * Specified location of MATLAB executable. If none is ever provided then an OS specific value is used.
     */
    private final String _matlabLocation;
    
    /**
     * The location of this support code. This location is provided to MATLAB so that it can add the location of this
     * code to its classpath.
     */
    private final String _supportCodeLocation;
    
    /**
     * Default number of milliseconds to wait for a {@link MatlabInternalProxy} to be received.
     */
    private static final int DEFAULT_TIMEOUT = 60000;
    
    /**
     * The duration (in milliseconds) between checks to determine if the proxies are still connected.
     */
    private static final int CONNECTION_CHECK_PERIOD = 1000;
    
    /**
     * Map of proxyIDs to {@link RemoteMatlabProxy} instances.
     */
    private final Map<String, RemoteMatlabProxy> _proxies = new HashMap<String, RemoteMatlabProxy>();

    /**
     * The RMI registry used to communicate between JVMs. There is only ever one registry actually running on a given
     * machine, so multiple distinct programs making use of matlabcontrol all share the same underlying registry
     * (although the Java object will be different).
     */
    private static Registry _registry = null;
    
    /**
     * Receiver for proxies created and sent over RMI.
     */
    private final MatlabInternalProxyReceiver _receiver = new ProxyReceiver();
    
    /**
     * Value used to bind the {@link ProxyReceiver}, as a {@link MatlabInternalProxyReceiver} so that it can be
     * retrieved from within the MATLAB JVM with this value.
     */
    private final String _receiverID = getRandomValue();
    
    /**
     * Whether this factory has been shutdown via {@link #shutdown()}. Once the factory has been shutdown, all
     * subsequent calls to create proxies will fail.
     */
    private boolean _isShutdown = false;
    
    /**
     * Constructs this factory with a specified location or alias for the MATLAB executable. Typically this will not be
     * necessary and so using the {@link #RemoteMatlabProxyFactory() the other constructor} will be the preferred
     * option. The location or alias specified by {@code matlabLocation} will not be verified at construction time,
     * and so if the value is invalid it will not cause this constructor to throw an exception. If it is invalid then an
     * exception will be thrown when creating a proxy with {@link #requestProxy()}, {@link #getProxy()}, or
     * {@link #getProxy(long)}.
     * 
     * @param matlabLocation
     * 
     * @throws MatlabConnectionException thrown if the initialization necessary for connecting to MATLAB cannot be
     *                                   properly configured
     */
    public RemoteMatlabProxyFactory(String matlabLocation) throws MatlabConnectionException
    {
        //Store location/alias of the MATLAB executable
        _matlabLocation = matlabLocation;

        //Location of where this code is
        _supportCodeLocation = Configuration.getSupportCodeLocation();
        
        //Initialize the registry
        initRegistry();
        
        //Bind the receiver to be retrieved from MATLAB
        this.bindReceiver();
    }
    
    /**
     * Constructs this factory with a default location or alias for the MATLAB executable (on an operating system
     * specific basis). If that default does not work, then construct this class with 
     * {@link #RemoteMatlabProxyFactory(String) the other constructor} so that the correct location or alias can be
     * specified.
     * 
     * @throws MatlabConnectionException thrown if the initialization necessary for connecting to MATLAB cannot be
     *                                   properly configured
     */
    public RemoteMatlabProxyFactory() throws MatlabConnectionException
    {
        this(Configuration.getMatlabLocation());
    }
    
    /**
     * Initializes the registry if it has not already been set up. Specifies the codebase so that paths with spaces in
     * them will work properly.
     * 
     * @throws MatlabConnectionException
     */
    private static void initRegistry() throws MatlabConnectionException
    {
        //If the registry hasn't been created
        if(_registry == null)
        {
            //Create a RMI registry
            try
            {
                _registry = LocateRegistry.createRegistry(Registry.REGISTRY_PORT);
            }
            //If we can't create one, try to retrieve an existing one
            catch (Exception e)
            {
                try
                {
                    _registry = LocateRegistry.getRegistry(Registry.REGISTRY_PORT);
                }
                catch(Exception ex)
                {
                    throw new MatlabConnectionException("Could not create or connect to the RMI registry", ex);
                }
            }

            //Tell the code base where it is, and just to be safe force it to use it
            //(This is necessary so that paths with spaces work properly)
            System.setProperty("java.rmi.server.codebase", Configuration.getCodebaseLocation());
            System.setProperty("java.rmi.server.useCodebaseOnly", "true");
        }
    }
    
    /**
     * Binds the receiver for RMI so it can be retrieved from the MATLAB JVM as a {@link MatlabInternalProxyReceiver}.
     * 
     * @throws MatlabConnectionException
     */
    private void bindReceiver() throws MatlabConnectionException
    {
        try
        {
            _registry.bind(_receiverID, UnicastRemoteObject.exportObject(_receiver, 0));
        }
        catch(Exception e)
        {
            throw new MatlabConnectionException("Could not bind proxy receiever to the RMI registry", e);
        }
    }
    
    /**
     * Receives the inner proxy from MATLAB. This inner class exists to hide the
     * {@link MatlabInternalProxyReceiver#registerControl(String, MatlabInternalProxy)} method which must be public
     * because it is implementing an interface; however, this method should not be visible to users of the API so
     * instead it is hidden inside of this private class.
     */
    private class ProxyReceiver implements MatlabInternalProxyReceiver
    {
        /**
         * This method is to be called by {@link MatlabConnector} running inside
         * of the MATLAB JVM.
         * 
         * @param proxyID the identifier for this proxy
         * @param internalProxy the proxy used internally
         */
        @Override
        public void registerControl(String proxyID, MatlabInternalProxy internalProxy)
        {
            //Create proxy, store it
            RemoteMatlabProxy proxy = new RemoteMatlabProxy(internalProxy, proxyID);
            _proxies.put(proxyID, proxy);
            
            //Wake up the thread potentially waiting for the proxy
            synchronized(RemoteMatlabProxyFactory.this)
            {
                RemoteMatlabProxyFactory.this.notifyAll();
            }
            _listenerManager.connectionEstablished(proxy);
            
            //Create the timer, if necessary, which checks if proxies are still connected
            RemoteMatlabProxyFactory.this.initConnectionTimer();
        }
    }
    
    /**
     * Generates a random value to be used in binding and proxy IDs.
     * 
     * @return random value
     */
    private static String getRandomValue()
    {
        return UUID.randomUUID().toString();
    }
    

    public String requestProxy() throws MatlabConnectionException
    {
        if(_isShutdown)
        {
            throw new MatlabConnectionException("This factory has been shutdown");
        }
        
        //Unique ID for proxy
        String proxyID = getRandomValue();
        
        //Argument that MATLAB will run on start.
        //Tells MATLAB to add this code to its classpath, then to call a method which
        //will create a proxy and send it over RMI back to this JVM.
        String runArg = "javaaddpath '" + _supportCodeLocation + "'; " +
                        MatlabConnector.class.getName() + 
                        ".connectFromMatlab('" + _receiverID + "', '" + proxyID + "');";
        
        //Attempt to run MATLAB
        try
        {
            Runtime.getRuntime().exec(new String[]{ _matlabLocation, "-desktop", "-r", runArg });
        }
        catch (IOException e)
        {
            throw new MatlabConnectionException("Could not launch MATLAB. Used location/alias: " + _matlabLocation, e);
        }
        
        return proxyID;
    }
    

    public MatlabProxy getProxy() throws MatlabConnectionException
    {
        return this.getProxy(DEFAULT_TIMEOUT);
    }
    

    @Override
    public MatlabProxy getProxy(long timeout) throws MatlabConnectionException
    {
        String proxyID = this.requestProxy();
        
        //Wait until the controller is received or until timeout
        synchronized(this)
        {
            try
            {
                this.wait(timeout);
            }
            catch (InterruptedException e)
            {
                throw new MatlabConnectionException("Thread was interrupted while waiting for MATLAB proxy", e);
            }
        }
        
        //If the proxy has not be received before the timeout
        if(!_proxies.containsKey(proxyID))
        {
            throw new MatlabConnectionException("MATLAB proxy could not be created in the specified amount of time: " +
                    timeout + " milliseconds");
        }
        
        return _proxies.get(proxyID);
    }
    
    @Override
    public void addConnectionListener(MatlabConnectionListener listener)
    {
        _listenerManager.addConnectionListener(listener);
    }
    
    @Override
    public void removeConnectionListener(MatlabConnectionListener listener)
    {
        _listenerManager.removeConnectionListener(listener);
    }
    
    @Override
    public void shutdown() throws MatlabConnectionException
    {      
        if(!_isShutdown)
        {
            //Unexport the receiver so that the RMI threads can shut down
            try
            {
                UnicastRemoteObject.unexportObject(_receiver, true);
            }
            catch(NoSuchObjectException e)
            {
                throw new MatlabConnectionException("Unable to deregister listener of incoming proxies", e);
            }

            //Wait for the proxies to disconnect so that listeners can be notified
            try
            {
                Thread.sleep(CONNECTION_CHECK_PERIOD);
            }
            catch(InterruptedException e)
            {
                throw new MatlabConnectionException("Unable to wait for proxies to disconnect", e);
            }

            //Stop other threads: shutdown the listener manager and stop the timer
            _listenerManager.shutdown();
            
            if(_connectionTimer != null)
            {
                _connectionTimer.cancel();
            }

            //Record shutdown
            _isShutdown = true;
        }
    }
    
    @Override
    public boolean isShutdown()
    {
        return _isShutdown;
    }
    
    public static void main(String[] args) throws Throwable
    {
        System.out.println("Starting");
        
        RemoteMatlabProxyFactory factory = new RemoteMatlabProxyFactory();
        factory.addConnectionListener(new MatlabConnectionListener()
        {
            @Override
            public void connectionEstablished(MatlabProxy proxy)
            {
                System.out.println("Established: " + proxy);
            }

            @Override
            public void connectionLost(MatlabProxy proxy)
            {
                System.out.println("Lost: " + proxy);
            }
        });
        MatlabProxy proxy = factory.getProxy();
        
        proxy.eval(("disp('hello world')"));
        
        Thread.sleep(3000);
        
        proxy.exit();
        
        factory.shutdown();
        
        System.out.println("Shut down complete");
    }
    
    /**
     * Creates a timer, if it does not already exist, to check for lost proxy connections.
     */    
    private void initConnectionTimer()
    {
        //If there is no timer yet, create a timer to monitor the connections
        if(_connectionTimer == null)
        {
            _connectionTimer = new Timer();
            _connectionTimer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    RemoteMatlabProxyFactory.this.checkConnections();
                }                
            }, CONNECTION_CHECK_PERIOD, CONNECTION_CHECK_PERIOD);
        }
    }
    
    /**
     * Checks the connections to MATLAB. If a connection has died, the listeners are informed and all references to it
     * by this class are removed.
     */
    private void checkConnections()
    {
        //Proxies that have become disconnected
        final ArrayList<RemoteMatlabProxy> disconnectedProxies = new ArrayList<RemoteMatlabProxy>();

        //Check each proxy's connection, if it has died add to disconnectedProxies
        synchronized(_proxies)
        {
            ArrayList<String> proxyKeys = new ArrayList<String>(_proxies.keySet());
            for(String proxyKey : proxyKeys)
            {
                RemoteMatlabProxy proxy = _proxies.get(proxyKey);
                if(!proxy.isConnected())
                {
                    _proxies.remove(proxyKey);
                    disconnectedProxies.add(proxy);
                }
            }
        }
        
        //Notify the listeners of the disconnected proxies
        for(RemoteMatlabProxy proxy : disconnectedProxies)
        {
            _listenerManager.connectionLost(proxy);
        }
    }
}