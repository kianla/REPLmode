package jm.mode.replmode;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import processing.app.Base;
import processing.app.RunnerListener;
import processing.app.SketchException;
import processing.app.exec.StreamRedirectThread;
import processing.core.PApplet;
import processing.mode.java.JavaBuild;
import processing.mode.java.runner.MessageSiphon;
import processing.mode.java.runner.Runner;

import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.Connector.Argument;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;

public class REPLRunner extends Runner {
  boolean isREPLWindowVisible;
  boolean hasFailedLoad;

  public REPLRunner(JavaBuild build, RunnerListener listener)
      throws SketchException {
    super(build, listener);
    
    isREPLWindowVisible = false;
  }

  public void launchREPL(boolean refresh) {
    // I <3 short circuiting
    if ((!isREPLWindowVisible || refresh) && launchREPLVirtualMachine()) {
      isREPLWindowVisible = true;
      generateREPLTrace();
      isREPLWindowVisible = false;
    }
  }

  /**
   * Generate the trace. Enable events, start thread to display events, start
   * threads to forward remote error and output streams, resume the remote VM,
   * wait for the final event, and shutdown. This is exactly the same as
   * generateTrace() method, except that the error handling is a tad bit
   * different, with the error displayed in the REPL Console instead of in the
   * status area.
   */
  protected void generateREPLTrace() {
    // Calling this seems to set something internally to make the
    // Eclipse JDI wake up. Without it, an ObjectCollectedException
    // is thrown on excReq.enable(). No idea why this works,
    // but at least exception handling has returned. (Suspect that it may
    // block until all or at least some threads are available, meaning
    // that the app has launched and we have legit objects to talk to).
    vm.allThreads();
    // The bug may not have been noticed because the test suite waits for
    // a thread to be available, and queries it by calling allThreads().
    // See org.eclipse.debug.jdi.tests.AbstractJDITest for the example.

    EventRequestManager mgr = vm.eventRequestManager();
    // get only the uncaught exceptions
    ExceptionRequest excReq = mgr.createExceptionRequest(null, false, true);
    // this version reports all exceptions, caught or uncaught

    // suspend so we can step
    excReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
    
    excReq.enable();

    Thread eventThread = new Thread() {
      public void run() {
        try {
          boolean connected = true;
          while (connected) {
            EventQueue eventQueue = vm.eventQueue();
            // remove() blocks until event(s) available
            EventSet eventSet = eventQueue.remove();

            for (Event event : eventSet) {
              if (event instanceof VMStartEvent) {
                vm.resume();
              } else if (event instanceof ExceptionEvent) {
                exceptionEvent((ExceptionEvent) event);
                
                // TODO: This is neat, but a wee bit hacky, is it not?
                if (editor != null && editor instanceof REPLEditor) {
                  String errMsg = editor.getStatusMessage();
                  editor.statusEmpty();
                  CommandPromptPane cmd = 
                      ((REPLEditor)editor).getCommandPromptPane();
                  if (errMsg.contains("/size_.html")) {
                    cmd.handleException("size() cannot be called from within" + 
                        " the REPL Console. Please use init or resize instead.");
                  }
                  else {
                    cmd.handleException(errMsg);
                  }
                close();
                connected = false;
                }
              } else if (event instanceof VMDisconnectEvent) {
                connected = false;
              }
            }
          }
        } catch (Exception e) {
          System.err.println("crashed in event thread due to " + e.getMessage());
          e.printStackTrace();
        }
      }
    };
    eventThread.start();


    errThread =
      new MessageSiphon(process.getErrorStream(), this).getThread();

    outThread = new StreamRedirectThread("JVM stdout Reader",
                                         process.getInputStream(),
                                         sketchOut);
    errThread.start();
    outThread.start();

    // Shutdown begins when event thread terminates
    try {
      if (eventThread != null) eventThread.join();  // is this the problem?

      // Bug #852 tracked to this next line in the code.
      // http://dev.processing.org/bugs/show_bug.cgi?id=852
      errThread.join(); // Make sure output is forwarded

      outThread.join(); // before we exit

      /* The run button is never enabled in the first place
      // TODO this should be handled better, should it not?
      if (editor != null) {
        editor.deactivateRun();
      }
      */
    } catch (InterruptedException exc) {
      // we don't interrupt
    }
  }

  public boolean launchREPLVirtualMachine() {
    return launchVirtualMachine(false);
  }

  @Override
  public boolean launchVirtualMachine(boolean presenting) {
    int port = 8000 + (int) (Math.random() * 1000);
    String portStr = String.valueOf(port);

    String[] vmParams = getMachineParams();
    String[] sketchParams = getSketchParams(presenting);    

    String hotSwapArg = "";
    URL url = REPLRunner.class.getProtectionDomain().getCodeSource().getLocation();
    File currentDirectory = null;
    try {
      currentDirectory = new File(url.toURI());
      hotSwapArg = "-javaagent:" + currentDirectory.getParentFile().getAbsolutePath() + "/hotswap-agent.jar=autoHotswap=true";
      hasFailedLoad = false;
    } catch (URISyntaxException e2) {
//      e2.printStackTrace();
      System.err.println("The hot swapper is feeling a little sleepy right now. Don't worry- REPL Mode will try to wake it up");
      hasFailedLoad = true;
    }
    // Newer (Java 1.5+) version that uses JVMTI
    String jdwpArg = "-agentlib:jdwp=transport=dt_socket,address=" + portStr + ",server=y,suspend=y";
    // Everyone works the same under Java 7 (also on OS X)
    String[] commandArgs = new String[] { Base.getJavaPath(), jdwpArg,hotSwapArg };
    commandArgs = PApplet.concat(commandArgs, vmParams);
    commandArgs = PApplet.concat(commandArgs, sketchParams);
    launchJava(commandArgs);

    /*
     * This part seems to be used to get the vm, that is in turn used not only
     * for the debugger, but to close the sketch frame as well...
     */
    AttachingConnector connector = (AttachingConnector)
      findConnector("com.sun.jdi.SocketAttach");

    Map<String, Argument> arguments = connector.defaultArguments();

    Connector.Argument portArg = arguments.get("port");
    portArg.setValue(portStr);

    try {
      while (true) {
        try {
          vm = connector.attach(arguments);
          if (vm != null) {
            return true;
          }
        } catch (IOException e) {
          try {
            Thread.sleep(100);
          } catch (InterruptedException e1) {
            e1.printStackTrace(sketchErr);
          }
        }
      }
    } catch (IllegalConnectorArgumentsException exc) {
      throw new Error("Internal error: " + exc);
    }
  }
  
  boolean isFailedLoad() {
    return hasFailedLoad;
  }
}
