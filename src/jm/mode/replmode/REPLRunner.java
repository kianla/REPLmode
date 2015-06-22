package jm.mode.replmode;

import java.io.IOException;
import java.util.Map;

import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.Connector.Argument;

import processing.app.Base;
import processing.app.RunnerListener;
import processing.app.SketchException;
import processing.core.PApplet;
import processing.mode.java.JavaBuild;
import processing.mode.java.runner.Runner;

public class REPLRunner extends Runner {
  String portStr;

  public REPLRunner(JavaBuild build, RunnerListener listener)
      throws SketchException {
    super(build, listener);
  }

  public void launchREPL() {
    if (launchREPLVirtualMachine()) {
      generateTrace();
    }
  }
  
  public void recompileREPL() {
//    System.out.println("vm is: " + );
    String[] vmParams = getMachineParams();
    String[] sketchParams = getSketchParams(false);    

    // Newer (Java 1.5+) version that uses JVMTI
//    String jdwpArg = "-agentlib:jdwp=transport=dt_socket,address=" + portStr + ",server=y,suspend=y";
    String hotSwapArg = /*"-XXaltjvm=dcevm */"-javaagent:C:\\Users\\Joel\\Documents\\Code\\p5\\REPLmode\\lib\\hotswap-agent.jar=autoHotswap=true";
    // Everyone works the same under Java 7 (also on OS X)
    String[] commandArgs = new String[] { Base.getJavaPath(), hotSwapArg };
    System.out.println(Base.getJavaPath());
    commandArgs = PApplet.concat(commandArgs, vmParams);
    commandArgs = PApplet.concat(commandArgs, sketchParams);
    launchJava(commandArgs);
  }
  
  public boolean launchREPLVirtualMachine() {
    recompileREPL();
    return false;
 /*   
    int port = 8000 + (int) (Math.random() * 1000);
    portStr = String.valueOf(port);

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
  */
  }
}