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

import java.util.HashMap;
import java.util.Map;

import com.mathworks.jmi.Matlab;
import com.mathworks.jmi.MatlabException;
import com.mathworks.jmi.NativeMatlab;

/**
 * This code is inspired by <a href="mailto:whitehouse@virginia.edu">Kamin Whitehouse</a>'s
 * <a href="http://www.cs.virginia.edu/~whitehouse/matlab/JavaMatlab.html">MatlabControl</a>.
 * <br><br>
 * This class runs inside of the MATLAB Java Virtual Machine and relies upon the {@code jmi.jar} which is distributed
 * with MATLAB in order to send commands to MATLAB and receive results.
 * <br><br>
 * Only this class and {@link MatlabInternalException} directly interact with {@code jmi.jar}.
 *
 * @author <a href="mailto:jak2@cs.brown.edu">Joshua Kaplan</a>
 */
class JMIWrapper
{
    /**
     * The return value from blocking methods.
     */
    private Object _returnValue;
    
    /**
     * A MatlabException that may be thrown during execution of {@link #returningEval(String, int)},
     * {@link #returningFeval(String, Object[])}, or {@link #returningFeval(String, Object[], int)}. The exception must
     * be stored as the direction cannot be thrown directly because it is inside of a {@link Runnable}.
     */
    private MatlabException _thrownException = null;

    /**
     * The default value that {@link JMIWrapper#_returnVal} is set to before an actual return value is returned.
     */
    private static final String BEFORE_RETURN_VALUE = "noReturnValYet";
    
    /**
     * Map of variables used by {@link #getVariableValue(String)}, and {@link #setVariable(String, Object)}.
     */
    private static final Map<String, Object> VARIABLES = new HashMap<String, Object>();
    
    /**
     * The name of this class and package.
     */
    private static final String CLASS_NAME = JMIWrapper.class.getName();
    
    /**
     * Gets the variable value stored by {@link #setVariable(String, Object)}.
     * 
     * @param variableName
     * 
     * @return variable value
     */
    public static Object retrieveVariableValue(String variableName)
    {
        Object result = VARIABLES.get(variableName);
        VARIABLES.remove(variableName);
        
        return result;
    }
    
    /**
     * Sets the variable to the given value. This is done by storing the variable in Java and then retrieving it in
     * MATLAB by calling a Java method that will return it.
     * 
     * @param variableName
     * @param value
     * 
     * @throws MatlabInvocationException
     */
    void setVariable(String variableName, Object value) throws MatlabInvocationException
    {
        VARIABLES.put(variableName, value);
        this.eval(variableName + " = " + CLASS_NAME + ".retrieveVariableValue('" + variableName + "');");
    }
    
    /**
     * Convenience method to retrieve a variable's value from MATLAB.
     * 
     * @param variableName
     * 
     * @throws MatlabInvocationException
     */
    Object getVariable(String variableName) throws MatlabInvocationException
    {
        return this.returningEval(variableName, 1);
    }
    
    /**
     * @see MatlabProxy#exit()
     */
    void exit() throws MatlabInvocationException
    {
        Matlab.whenMatlabReady(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Matlab.mtFevalConsoleOutput("exit", null, 0);
                }
                catch (Exception e) { }
            }
        });
    }
    
    /**
     * @see MatlabProxy#eval(java.lang.String)
     */
    void eval(final String command) throws MatlabInvocationException
    {   
        this.returningEval(command, 0);
    }
    
    /**
     * @see MatlabProxy#returningEval(java.lang.String, int)
     */
    Object returningEval(final String command, final int returnCount) throws MatlabInvocationException
    {
        return this.returningFeval("eval", new Object[]{ command }, returnCount);
    }

    /**
     * @see MatlabProxy#returningFeval(java.lang.String, java.lang.Object[])
     */
    void feval(final String functionName, final Object[] args) throws MatlabInvocationException
    {
        this.returningFeval(functionName, args, 0);
    }

    /**
     * @see MatlabProxy#returningFeval(java.lang.String, java.lang.Object[], int)
     */
    Object returningFeval(final String functionName, final Object[] args, final int returnCount) throws MatlabInvocationException
    {
        if(isMatlabThread())
        {
            try
            {
                return Matlab.mtFevalConsoleOutput(functionName, args, returnCount);
            }
            catch (Exception e)
            {
                throw new MatlabInvocationException(MatlabInvocationException.INTERNAL_EXCEPTION_MSG, e);
            }
        }
        else
        {
            _returnValue = BEFORE_RETURN_VALUE;
            _thrownException = null;
            
            Matlab.whenMatlabReady(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        JMIWrapper.this.setReturnValue(Matlab.mtFevalConsoleOutput(functionName, args, returnCount));
                    }
                    catch(MatlabException e)
                    {
                        _thrownException = e;
                        JMIWrapper.this.setReturnValue(null);
                    }
                    catch(Exception e)
                    {
                        JMIWrapper.this.setReturnValue(null);
                    }
                }    
            });
            
            return this.getReturnValue();
        }
    }
    
    /**
     * @see MatlabProxy#returningFeval(java.lang.String, java.lang.Object[])
     */
    Object returningFeval(final String functionName, final Object[] args) throws MatlabInvocationException
    {
        //Get the number of arguments that will be returned
        Object result = this.returningFeval("nargout", new String[] { functionName }, 1);
        int nargout = 0;
        try
        {
            nargout = (int) ((double[]) result)[0];
            
            //If an unlimited number of arguments (represented by -1), choose 1
            if(nargout == -1)
            {
                nargout = 1;
            }
        }
        catch(Exception e) {}
        
        //Send the request
        return this.returningFeval(functionName, args, nargout);
    }

    /**
     * @see MatlabProxy#setEchoEval(boolean)
     */
    void setEchoEval(final boolean echo)
    {
        if(isMatlabThread())
        {
            Matlab.setEchoEval(echo);
        }
        else
        {
            Matlab.whenMatlabReady(new Runnable()
            {
                @Override
                public void run()
                {
                    Matlab.setEchoEval(echo);
                }
            });
        }
    }
    
    /**
     * Returns the value returned by MATLAB to {@link #returningFeval(String, Object[], int)}. Throws a
     * {@link MatlabInvocationException} if the JMI call threw an exception.
     * <br><br>
     * This method operates by pausing the thread until MATLAB returns a value.
     * 
     * @return result of MATLAB call
     * 
     * @throws MatlabInvocationException
     * 
     * @see #setReturnValue(Object)
     */
    private Object getReturnValue() throws MatlabInvocationException
    {
        //If _returnVal has not been changed yet (in all likelihood it has not)
        //then wait, it will be resumed when the call to MATLAB returns
        if (_returnValue == BEFORE_RETURN_VALUE)
        {
            synchronized(_returnValue)
            {
                try
                {
                    _returnValue.wait();
                }
                catch (InterruptedException e)
                {
                    throw new MatlabInvocationException(MatlabInvocationException.INTERRUPTED_MSG, e); 
                }
            }
        }
        
        if(_thrownException != null)
        {
            throw new MatlabInvocationException(MatlabInvocationException.INTERNAL_EXCEPTION_MSG, new MatlabInternalException(_thrownException));
        }

        return _returnValue;
    }

    /**
     * Sets the return value from any of the eval or feval commands. In the case of the non-returning value {@code null}
     * is passed in, but it still provides the functionality of waking up the thread so that {@link #getReturnValue()}
     * will be able to return.
     * 
     * @param val
     * 
     * @see #getReturnValue()
     */
    private void setReturnValue(Object val)
    {
        synchronized(_returnValue)
        {      
            Object oldVal = _returnValue;
            _returnValue = val;
            oldVal.notifyAll();
        }
    }
    
    /**
     * Returns whether or not the calling thread is the main MATLAB thread.
     * 
     * @return if main MATLAB thread
     */
    private static boolean isMatlabThread()
    {
        return NativeMatlab.nativeIsMatlabThread();
    }
}