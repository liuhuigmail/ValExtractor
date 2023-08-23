package com.google.javascript.jscomp.deps;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.ErrorManager;
import com.google.javascript.jscomp.JSError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract public class JsFileLineParser  {
  final static DiagnosticType PARSE_WARNING = DiagnosticType.warning("DEPS_PARSE_WARNING", "{0}\n{1}");
  final static DiagnosticType PARSE_ERROR = DiagnosticType.error("DEPS_PARSE_ERROR", "{0}\n{1}");
  boolean shortcutMode = false;
  final private static Pattern STRING_LITERAL_PATTERN = Pattern.compile("\\s*(?:\'((?:\\\\\'|[^\'])*?)\'|\"((?:\\\\\"|[^\"])*?)\")\\s*");
  private Matcher valueMatcher = STRING_LITERAL_PATTERN.matcher("");
  String filePath;
  int lineNum;
  ErrorManager errorManager;
  boolean parseSucceeded;
  public JsFileLineParser(ErrorManager errorManager) {
    super();
    this.errorManager = errorManager;
  }
  List<String> parseJsStringArray(String input) throws ParseException {
    List<String> results = Lists.newArrayList();
    int indexStart = input.indexOf('[');
    int indexEnd = input.lastIndexOf(']');
    if((indexStart == -1) || (indexEnd == -1)) {
      throw new ParseException("Syntax error when parsing JS array", true);
    }
    String innerValues = input.substring(indexStart + 1, indexEnd);
    if(!innerValues.trim().isEmpty()) {
      valueMatcher.reset(innerValues);
      for(; true; ) {
        if(!valueMatcher.lookingAt()) {
          throw new ParseException("Syntax error in JS String literal", true);
        }
        results.add(valueMatcher.group(1) != null ? valueMatcher.group(1) : valueMatcher.group(2));
        if(valueMatcher.hitEnd()) {
          break ;
        }
        int var_2142 = valueMatcher.end();
        if(innerValues.charAt(var_2142) != ',') {
          throw new ParseException("Missing comma in string array", true);
        }
        valueMatcher.region(valueMatcher.end() + 1, valueMatcher.regionEnd());
      }
    }
    return results;
  }
  String parseJsString(String jsStringLiteral) throws ParseException {
    valueMatcher.reset(jsStringLiteral);
    if(!valueMatcher.matches()) {
      throw new ParseException("Syntax error in JS String literal", true);
    }
    return valueMatcher.group(1) != null ? valueMatcher.group(1) : valueMatcher.group(2);
  }
  public boolean didParseSucceed() {
    return parseSucceeded;
  }
  abstract boolean parseLine(String line) throws ParseException;
  void doParse(String filePath, Reader fileContents) {
    this.filePath = filePath;
    parseSucceeded = true;
    BufferedReader lineBuffer = new BufferedReader(fileContents);
    String line = null;
    lineNum = 0;
    boolean inMultilineComment = false;
    try {
      while(null != (line = lineBuffer.readLine())){
        ++lineNum;
        try {
          String revisedLine = line;
          if(inMultilineComment) {
            int endOfComment = revisedLine.indexOf("*/");
            if(endOfComment != -1) {
              revisedLine = revisedLine.substring(endOfComment + 2);
              inMultilineComment = false;
            }
            else {
              revisedLine = "";
            }
          }
          if(!inMultilineComment) {
            while(true){
              int startOfLineComment = revisedLine.indexOf("//");
              int startOfMultilineComment = revisedLine.indexOf("/*");
              if(startOfLineComment != -1 && (startOfMultilineComment == -1 || startOfLineComment < startOfMultilineComment)) {
                revisedLine = revisedLine.substring(0, startOfLineComment);
                break ;
              }
              else 
                if(startOfMultilineComment != -1) {
                  int endOfMultilineComment = revisedLine.indexOf("*/", startOfMultilineComment + 2);
                  if(endOfMultilineComment == -1) {
                    revisedLine = revisedLine.substring(0, startOfMultilineComment);
                    inMultilineComment = true;
                    break ;
                  }
                  else {
                    revisedLine = revisedLine.substring(0, startOfMultilineComment) + revisedLine.substring(endOfMultilineComment + 2);
                  }
                }
                else {
                  break ;
                }
            }
          }
          if(!revisedLine.isEmpty()) {
            if(!parseLine(revisedLine) && shortcutMode) {
              break ;
            }
          }
        }
        catch (ParseException e) {
          errorManager.report(e.isFatal() ? CheckLevel.ERROR : CheckLevel.WARNING, JSError.make(filePath, lineNum, 0, e.isFatal() ? PARSE_ERROR : PARSE_WARNING, e.getMessage(), line));
          parseSucceeded = parseSucceeded && !e.isFatal();
        }
      }
    }
    catch (IOException e) {
      errorManager.report(CheckLevel.ERROR, JSError.make(filePath, 0, 0, PARSE_ERROR, "Error reading file: " + filePath));
      parseSucceeded = false;
    }
  }
  public void setShortcutMode(boolean mode) {
    this.shortcutMode = mode;
  }
  
  static class ParseException extends Exception  {
    final public static long serialVersionUID = 1L;
    private boolean fatal;
    public ParseException(String message, boolean fatal) {
      super(message);
      this.fatal = fatal;
    }
    public boolean isFatal() {
      return fatal;
    }
  }
}