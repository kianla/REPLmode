package jm.mode.replmode;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.NavigationFilter;
import javax.swing.text.Position;
import javax.swing.text.Utilities;

/**
 * Class responsible for setting up a NavigationFilter that makes a JTextArea
 * have command-prompt-esque properties.
 * 
 * UI Code adapted from <a
 * href=http://www.coderanch.com/t/508726/GUI/java/creating
 * -custom-command-prompt-java#post_text_2299445>here</a>.
 */
public class CommandPromptPane extends NavigationFilter {
  private int prefixLength;

  private Action deletePrevious;

  private Action shiftLine;

  JTextArea consoleArea;

  CommandHistory commandHistManager;

  CommandList commandListManager;

  REPLEditor replEditor;

  String prompt;

  String promptContinuation;

  boolean isContinuing;

  int openLeftCurlies;

  int rowStartPosition;

  public CommandPromptPane(String prompt, String promptContinuation,
                           REPLEditor editor, JTextArea component) {
    consoleArea = component;
    commandHistManager = new CommandHistory();
    commandListManager = new CommandList(this);
    replEditor = editor;
    this.prompt = prompt;
    this.promptContinuation = promptContinuation;
    this.prefixLength = prompt.length();
    isContinuing = false;
    openLeftCurlies = 0;
    rowStartPosition = 0;

    // TODO: Check these next 4 lines out. Refactor later if necessary.
    deletePrevious = component.getActionMap().get("delete-previous");
    shiftLine = component.getActionMap().get("insert-break");
    component.getActionMap().put("delete-previous", new BackspaceAction());
    component.getActionMap().put("insert-break", new EnterAction());

    component.getInputMap()
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "up");
    component.getActionMap().put("up", new KeyAction("up"));
    component.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
                                "down");
    component.getActionMap().put("down", new KeyAction("down"));

    component.setCaretPosition(prefixLength);
    component.setLineWrap(true);
  }

  public void setDot(NavigationFilter.FilterBypass fb, int dot,
                     Position.Bias bias) {
    fb.setDot(Math.max(dot, rowStartPosition + prefixLength), bias);
  }

  public void moveDot(NavigationFilter.FilterBypass fb, int dot,
                      Position.Bias bias) {
    fb.moveDot(Math.max(dot, rowStartPosition + prefixLength), bias);
  }

  class BackspaceAction extends AbstractAction {
    private static final long serialVersionUID = -116059028248053840L;

    public void actionPerformed(ActionEvent e) {
      JTextArea component = (JTextArea) e.getSource();

      if (getColumn(component) > prefixLength + 1) {
        deletePrevious.actionPerformed(null);
      }
    }
  }

  class EnterAction extends AbstractAction {
    private static final long serialVersionUID = 2813908067205522536L;

    public void actionPerformed(ActionEvent e) {
      JTextArea component = (JTextArea) e.getSource();
      component.setCaretPosition(component.getText().length());
      String command = getLastLine();
      String trimmedCommand = command.trim();
      String firstCommandWord = command.split(" ")[0];
      shiftLine.actionPerformed(null);
      commandHistManager.insertCommand(command);

      if (Arrays.asList(CommandList.REPL_COMMAND_SET)
          .contains(firstCommandWord)) {
        handleREPLModeCommand(trimmedCommand, component);
      } else {

        if (isContinuing || trimmedCommand.endsWith("{")
            || trimmedCommand.endsWith(",")) {
          handleContinuingStatement(trimmedCommand, component);
        } else {
          boolean error = commandListManager.addStatement(command);
          component.replaceSelection(prompt);
          prefixLength = prompt.length();
          runTempSketch(error, false);
        }

        try {
          rowStartPosition = Math.max(rowStartPosition, Utilities
              .getRowStart(consoleArea, consoleArea.getCaretPosition()));
        } catch (BadLocationException e1) {
          e1.printStackTrace();
        }
      }

    }
  }

  protected void handleContinuingStatement(String command, JTextArea component) {
    boolean error = commandListManager.addContinuingStatement(command);

    if (command.endsWith("}") || command.endsWith(";")) {
      if (command.endsWith("}")) {
        openLeftCurlies--;
      }
      if (openLeftCurlies == 0) {
        commandListManager.endContinuingStatement();
        component.replaceSelection(prompt);
        prefixLength = prompt.length();
        isContinuing = false;
        runTempSketch(error, false);
      } else {
        component.replaceSelection(promptContinuation);
        prefixLength = promptContinuation.length();
        isContinuing = true;
      }
    } else {
      component.replaceSelection(promptContinuation);
      prefixLength = promptContinuation.length();
      isContinuing = true;

      if (command.endsWith("{")) {
        openLeftCurlies++;
      }
    }
  }

  protected void handleREPLModeCommand(String command, JTextArea component) {
    boolean isDone = true;
    boolean refresh = false;
    String firstCommandWord = command.split(" ")[0];
    if (command.equals(CommandList.CLEAR_COMMAND)) {
      // TODO: Or is selecting everything and then using replaceSelection() better?
//    component.select(0, component.getText().length());
//    component.replaceSelection(prompt);

      openLeftCurlies = 0;
      isContinuing = false;
      component.setText(prompt);

      // Don't clear the screen and undo stack any more
//      commandListManager.clear();
      rowStartPosition = 0;

    } else if (firstCommandWord.equals(CommandList.INIT_COMMAND)) {
      isDone = handleInit(command, false);
      component.setText(prompt + command + '\n' + prompt);
      try {
        int cp = consoleArea.getCaretPosition();
        rowStartPosition = Utilities.getRowStart(consoleArea, cp);
        refresh = true;
      } catch (BadLocationException e1) {
        e1.printStackTrace();
      }
    } else if (firstCommandWord.equals(CommandList.RESIZE_COMMAND)) {
      if (isContinuing) {
        printStatusMessage("Oops! REPL Mode is in the midst of another command (block)");
        isDone = false;
        component.replaceSelection(promptContinuation);
      } else {
        isDone = handleInit(command, true);
        component.replaceSelection(prompt);
        refresh = true;
      }
      try {
        rowStartPosition = Math.max(rowStartPosition, Utilities
            .getRowStart(consoleArea, consoleArea.getCaretPosition()));
      } catch (BadLocationException e1) {
        e1.printStackTrace();
      }
    } else if (firstCommandWord.equals(CommandList.UNDO_COMMAND)) {
      if (isContinuing) {
        printStatusMessage("Oops! REPL Mode is in the midst of another command (block)");
        isDone = false;
        component.replaceSelection(promptContinuation);
      } else {
        isDone = handleUndo(command, false);
        component.replaceSelection(prompt);
        refresh = true;
      }
      try {
        rowStartPosition = Math.max(rowStartPosition, Utilities
            .getRowStart(consoleArea, consoleArea.getCaretPosition()));
      } catch (BadLocationException e1) {
        e1.printStackTrace();
      }
    } else if (firstCommandWord.equals(CommandList.REDO_COMMAND)) {
      if (isContinuing) {
        printStatusMessage("Oops! REPL Mode is in the midst of another command (block)");
        isDone = false;
        component.replaceSelection(promptContinuation);
      } else {
        isDone = handleUndo(command, true);
        component.replaceSelection(prompt);
      }
      try {
        rowStartPosition = Math.max(rowStartPosition, Utilities
            .getRowStart(consoleArea, consoleArea.getCaretPosition()));
      } catch (BadLocationException e1) {
        e1.printStackTrace();
      }
    } else if (firstCommandWord.equals(CommandList.PRINT_COMMAND)) {
      // Always have isDone as false, since we really don't want anything to get updated
      isDone = false;
      if (isContinuing) {
        printStatusMessage("Oops! REPL Mode is in the midst of another command (block)");
        component.replaceSelection(promptContinuation);
      } else {
        handlePrintCode(command);
        component.replaceSelection(prompt);
      }
      try {
        rowStartPosition = Math.max(rowStartPosition, Utilities
            .getRowStart(consoleArea, consoleArea.getCaretPosition()));
      } catch (BadLocationException e1) {
        e1.printStackTrace();
      }
    }

    prefixLength = prompt.length();

    runTempSketch(!isDone, refresh); // since !isDone ==> isError

  }

  private boolean handleInit(String arg, boolean isReInit) {
    String args[] = arg.split("\\s+");
    boolean wasSuccess = false;
    if (args.length == 1) {
      if (isReInit) {
        commandListManager.reinit();
      } else {
        commandListManager.init();
      }
      wasSuccess = true;
    } else if (args.length == 3 || args.length == 4) {
      int w = 100, h = 100, errCount = 0;
      wasSuccess = true;
      String err = "Error: ";

      try {
        w = Integer.parseInt(args[1]);
      } catch (NumberFormatException nfe) {
        err += "w=" + args[1];
        wasSuccess = false;
        errCount++;
      }

      try {
        h = Integer.parseInt(args[2]);
      } catch (NumberFormatException nfe) {
        errCount++;
        if (!wasSuccess) {
          err += " and ";
        }
        err += "h=" + args[2];
        wasSuccess = false;
      }

      if (!wasSuccess) {
        if (errCount == 1) {
          err += " is not an integer";
        } else if (errCount == 2) {
          err += " are not integers";
        }
      }

      if (args.length == 4
          && !Arrays.asList(CommandList.SIZE_RENDERERS).contains(args[3])) {
        if (!wasSuccess) {
          err += " and ";
        }
        wasSuccess = false;
        err += "\"" + args[3] + "\" renderer is undefined (only ";
        for (int i = 0; i < CommandList.SIZE_RENDERERS.length; i++) {
          if (i != CommandList.SIZE_RENDERERS.length - 1) {
            err += "\"" + CommandList.SIZE_RENDERERS[i] + "\", ";
          } else {
            err += "and \"" + CommandList.SIZE_RENDERERS[i] + "\"";
          }
        }
        err += " renderers may be used)";
      }

      if (wasSuccess) {
        if (args.length == 3) {
          if (isReInit) {
            commandListManager.reinit(w, h);
          } else {
            commandListManager.init(w, h);
          }
        } else {
          if (isReInit) {
            commandListManager.reinit(w, h, args[3]);
          } else {
            commandListManager.init(w, h, args[3]);
          }
        }
      } else {
        printStatusMessage(err);
      }
    }

    return wasSuccess;
  }

  private boolean handleUndo(String arg, boolean isRedo) {
    String[] undo = arg.split("\\s+");
    boolean wasSuccess = true;
    int k = 0;
    if (undo.length == 1) {
      if (!isRedo) {
        k = commandListManager.undo(1);
      } else {
        k = commandListManager.redo(1);
      }
    } else if (undo.length == 2) {
      int n;
      try {
        n = Integer.parseInt(undo[1]);
        if (!isRedo) {
          k = commandListManager.undo(n);
        } else {
          k = commandListManager.redo(n);
        }
      } catch (NumberFormatException nfe) {
        printStatusMessage("Error: n=" + undo[1] + " is not an integer");
        wasSuccess = false;
      }
    } else {
      wasSuccess = false;
      printStatusMessage("Error: undo command should have only 0 or 1 arguments");
    }

    if (wasSuccess) {
      if (k == 0) {
        printStatusMessage("Nothing to " + (!isRedo ? "undo" : "redo"));
        wasSuccess = false;
      } else if (k == 1) {
        printStatusMessage("1 statement " + (!isRedo ? "undone" : "redone"));
      } else {
        printStatusMessage(k + " statements " + (!isRedo ? "undone" : "redone"));
      }
    }
    return wasSuccess;
  }

  private void handlePrintCode(String command) {
    String[] args = command.split("\\s+");
    if (args.length != 2) {
      if (args.length > 2) {
        printStatusMessage("Error: print should have only "
            + "a single function name as argument");
      } else {
        printStatusMessage("Error: print should have only "
            + "a single function name as argument");
      }
    } else if (!commandListManager.hasStuffToPrint()) {
      printStatusMessage("Nothing to print into a function yet.");
    } else {
      if (!isValidFunctionName(args[1])) {
        printStatusMessage("Error: \"" + args[1] + "\""
            + " is not a valid function name");
      } else {
        String code = commandListManager.getCodeFunction(args[1]);
        replEditor.setText(replEditor.getText() + "\n" + code);
      }
    }
  }

  private boolean isValidFunctionName(String fn) {
    // TODO: Is this correct?
    Pattern FN_NAME_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    return FN_NAME_PATTERN.matcher(fn).find();
  }

  protected void runTempSketch(boolean error, boolean refresh) {
    if (replEditor != null && !error) {
      try {
        String code = commandListManager.getREPLSketchCode();
        replEditor.handleREPLRun(code, refresh);
      } catch (Exception exc) {
        exc.printStackTrace();
      }
    }
  }

  class KeyAction extends AbstractAction {

    private static final long serialVersionUID = 3382543935199626852L;

    private String key;

    public KeyAction(String string) {
      key = string;
    }

    public void actionPerformed(ActionEvent e) {
      String cycledCommand = "";
      JTextArea component = (JTextArea) e.getSource();
      String prevCommand = getLastLine();
      if (key.equals("up")) {
        cycledCommand = commandHistManager.getPreviousCommand(prevCommand);
      } else if (key.equals("down")) {
        cycledCommand = commandHistManager.getNextCommand(prevCommand);
      }

      if (isContinuing) {
        component.replaceRange(cycledCommand,
                               component.getText()
                                   .lastIndexOf(promptContinuation)
                                   + promptContinuation.length(), component
                                   .getText().length());
      } else {
        component.replaceRange(cycledCommand,
                               component.getText().lastIndexOf(prompt)
                                   + prompt.length(), component.getText()
                                   .length());
      }
    }
  }

  /**
   * Prints a status message on a new line
   * 
   * @param msg
   *          The status message
   */
  public void printStatusMessage(String msg) {
    consoleArea.replaceSelection(msg + "\n");// + prompt);
  }

  /**
   * Prints the exception in the REPL Pane. Based on the Editor's statusError()
   * method.
   * 
   * @param e
   */
  public void printStatusException(Exception e) {
    e.printStackTrace();
    // TODO: Print line number
    /*
     * Sketch sketch = replEditor.getREPLTempSketch(); if (e instanceof
     * SketchException) { SketchException re = (SketchException) e; if
     * (re.hasCodeLine()) { int line = re.getCodeLine(); // subtract one from
     * the end so that the \n ain't included if (line >=
     * textarea.getLineCount()) { // The error is at the end of this current
     * chunk of code, // so the last line needs to be selected. line =
     * textarea.getLineCount() - 1; if (textarea.getLineText(line).length() ==
     * 0) { // The last line may be zero length, meaning nothing to select. //
     * If so, back up one more line. line--; } } if (line < 0 || line >=
     * textarea.getLineCount()) { System.err.println("Bad error line: " + line);
     * } else { textarea.select(textarea.getLineStartOffset(line),
     * textarea.getLineStopOffset(line) - 1); } } }
     */

    // Since this will catch all Exception types, spend some time figuring
    // out which kind and try to give a better error message to the user.
    String mess = e.getMessage();
    if (mess != null) {
      String javaLang = "java.lang.";
      if (mess.indexOf(javaLang) == 0) {
        mess = mess.substring(javaLang.length());
      }
      // The phrase "RuntimeException" isn't useful for most users
      String rxString = "RuntimeException: ";
      if (mess.startsWith(rxString)) {
        mess = mess.substring(rxString.length());
      }
      // This is just confusing for most PDE users (save it for Eclipse users)
      String illString = "IllegalArgumentException: ";
      if (mess.startsWith(illString)) {
        mess = mess.substring(illString.length());
      }

      printStatusError(mess);
    }
  }
  
  public void printStatusError(String mess) {
    int currPrefixLength = prefixLength;
    prefixLength = 0;
    int currPos = consoleArea.getCaretPosition();
    isContinuing = false;
    consoleArea.setSelectionStart(currPos - currPrefixLength);
    consoleArea.setSelectionEnd(currPos);
    printStatusMessage("Error: " + mess);
    consoleArea.setCaretPosition(consoleArea.getText().length());
    consoleArea.replaceSelection(prompt);
    consoleArea.setCaretPosition(consoleArea.getText().length());
    prefixLength = prompt.length();
    try {
      rowStartPosition = Math.max(rowStartPosition, Utilities
          .getRowStart(consoleArea, consoleArea.getCaretPosition()));
    } catch (BadLocationException e1) {
      e1.printStackTrace();
    }
  }

  public void handleException(Exception e) {
    printStatusException(e);
    undoLastStatement();
  }

  public void handleException(String err) {
    printStatusError(err);
    undoLastStatement();
  }
  
  public String getLastLine() {
    // TODO: Is there a more efficient way of extracting the last line of code?
    int lineStartLocation;
    if (isContinuing) {
      lineStartLocation = consoleArea.getText().lastIndexOf(promptContinuation)
          + promptContinuation.length();
    } else {
      lineStartLocation = consoleArea.getText().lastIndexOf(prompt)
          + prompt.length();
    }
    return consoleArea.getText().substring(lineStartLocation);
  }

  public void undoLastStatement() {
    commandListManager.removePreviousStatement();
  }

  // Refer : http://stackoverflow.com/a/2750099/2427542
  // Refer : http://stackoverflow.com/a/13375811/2427542
  /**
   * Use to get line number at which caret is placed.
   * 
   * Code adapted from http://java-sl.com/tip_row_column.html
   * 
   * @param console
   *          : The JTextArea console
   * @return Row number
   */
  public static int getRow(JTextArea console) {
    int pos = console.getCaretPosition();
    int rn = (pos == 0) ? 1 : 0;
    try {
      int offs = pos;
      while (offs > 0) {
        offs = Utilities.getRowStart(console, offs) - 1;
        rn++;
      }
    } catch (BadLocationException e) {
      e.printStackTrace();
    }
    return rn;
  }

  /**
   * Use to get location of column at which caret is placed. The column number
   * starts with 1.
   * 
   * Code adapted from http://java-sl.com/tip_row_column.html
   * 
   * @param console
   *          : The JTextArea console
   * @return Column number
   */
  public static int getColumn(JTextArea console) {
    int pos = console.getCaretPosition();
    try {
      return pos - Utilities.getRowStart(console, pos) + 1;
    } catch (BadLocationException e) {
      e.printStackTrace();
    }
    return -1;
  }

  /*
   * Convenience main() function for easy debugging.
   */
  public static void main(String args[]) throws Exception {

    JTextArea textField = new JTextArea(">> ", 20, 40);
    CommandPromptPane cmdPromptPane = new CommandPromptPane(">> ", "...  ",
                                                            null, textField);
    textField.setNavigationFilter(cmdPromptPane);

    JFrame frame = new JFrame("Navigation Filter Example");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.getContentPane().add(textField);
    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
  }
}