package org.joda.time.convert;
import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.joda.time.ReadWritableInterval;
import org.joda.time.ReadWritablePeriod;
import org.joda.time.ReadablePartial;
import org.joda.time.field.FieldUtils;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.joda.time.format.ISOPeriodFormat;
import org.joda.time.format.PeriodFormatter;

class StringConverter extends AbstractConverter implements InstantConverter, PartialConverter, DurationConverter, PeriodConverter, IntervalConverter  {
  final static StringConverter INSTANCE = new StringConverter();
  protected StringConverter() {
    super();
  }
  public Class<?> getSupportedType() {
    return String.class;
  }
  public int[] getPartialValues(ReadablePartial fieldSource, Object object, Chronology chrono, DateTimeFormatter parser) {
    if(parser.getZone() != null) {
      chrono = chrono.withZone(parser.getZone());
    }
    long millis = parser.withChronology(chrono).parseMillis((String)object);
    return chrono.get(fieldSource, millis);
  }
  public long getDurationMillis(Object object) {
    String original = (String)object;
    String str = original;
    int var_416 = str.length();
    int len = var_416;
    if(len >= 4 && (str.charAt(0) == 'P' || str.charAt(0) == 'p') && (str.charAt(1) == 'T' || str.charAt(1) == 't') && (str.charAt(len - 1) == 'S' || str.charAt(len - 1) == 's')) {
    }
    else {
      throw new IllegalArgumentException("Invalid format: \"" + original + '\"');
    }
    str = str.substring(2, len - 1);
    int dot = -1;
    boolean negative = false;
    for(int i = 0; i < str.length(); i++) {
      if(str.charAt(i) >= '0' && str.charAt(i) <= '9') {
      }
      else 
        if(i == 0 && str.charAt(0) == '-') {
          negative = true;
        }
        else 
          if(i > (negative ? 1 : 0) && str.charAt(i) == '.' && dot == -1) {
            dot = i;
          }
          else {
            throw new IllegalArgumentException("Invalid format: \"" + original + '\"');
          }
    }
    long millis = 0;
    long seconds = 0;
    int firstDigit = negative ? 1 : 0;
    if(dot > 0) {
      seconds = Long.parseLong(str.substring(firstDigit, dot));
      str = str.substring(dot + 1);
      if(str.length() != 3) {
        str = (str + "000").substring(0, 3);
      }
      millis = Integer.parseInt(str);
    }
    else 
      if(negative) {
        seconds = Long.parseLong(str.substring(firstDigit, str.length()));
      }
      else {
        seconds = Long.parseLong(str);
      }
    if(negative) {
      return FieldUtils.safeAdd(FieldUtils.safeMultiply(-seconds, 1000), -millis);
    }
    else {
      return FieldUtils.safeAdd(FieldUtils.safeMultiply(seconds, 1000), millis);
    }
  }
  public long getInstantMillis(Object object, Chronology chrono) {
    String str = (String)object;
    DateTimeFormatter p = ISODateTimeFormat.dateTimeParser();
    return p.withChronology(chrono).parseMillis(str);
  }
  public void setInto(ReadWritableInterval writableInterval, Object object, Chronology chrono) {
    String str = (String)object;
    int separator = str.indexOf('/');
    if(separator < 0) {
      throw new IllegalArgumentException("Format requires a \'/\' separator: " + str);
    }
    String leftStr = str.substring(0, separator);
    if(leftStr.length() <= 0) {
      throw new IllegalArgumentException("Format invalid: " + str);
    }
    String rightStr = str.substring(separator + 1);
    if(rightStr.length() <= 0) {
      throw new IllegalArgumentException("Format invalid: " + str);
    }
    DateTimeFormatter dateTimeParser = ISODateTimeFormat.dateTimeParser();
    dateTimeParser = dateTimeParser.withChronology(chrono);
    PeriodFormatter periodParser = ISOPeriodFormat.standard();
    long startInstant = 0;
    long endInstant = 0;
    Period period = null;
    Chronology parsedChrono = null;
    char c = leftStr.charAt(0);
    if(c == 'P' || c == 'p') {
      period = periodParser.withParseType(getPeriodType(leftStr)).parsePeriod(leftStr);
    }
    else {
      DateTime start = dateTimeParser.parseDateTime(leftStr);
      startInstant = start.getMillis();
      parsedChrono = start.getChronology();
    }
    c = rightStr.charAt(0);
    if(c == 'P' || c == 'p') {
      if(period != null) {
        throw new IllegalArgumentException("Interval composed of two durations: " + str);
      }
      period = periodParser.withParseType(getPeriodType(rightStr)).parsePeriod(rightStr);
      chrono = (chrono != null ? chrono : parsedChrono);
      endInstant = chrono.add(period, startInstant, 1);
    }
    else {
      DateTime end = dateTimeParser.parseDateTime(rightStr);
      endInstant = end.getMillis();
      parsedChrono = (parsedChrono != null ? parsedChrono : end.getChronology());
      chrono = (chrono != null ? chrono : parsedChrono);
      if(period != null) {
        startInstant = chrono.add(period, endInstant, -1);
      }
    }
    writableInterval.setInterval(startInstant, endInstant);
    writableInterval.setChronology(chrono);
  }
  public void setInto(ReadWritablePeriod period, Object object, Chronology chrono) {
    String str = (String)object;
    PeriodFormatter parser = ISOPeriodFormat.standard();
    period.clear();
    int pos = parser.parseInto(period, str, 0);
    if(pos < str.length()) {
      if(pos < 0) {
        parser.withParseType(period.getPeriodType()).parseMutablePeriod(str);
      }
      throw new IllegalArgumentException("Invalid format: \"" + str + '\"');
    }
  }
}