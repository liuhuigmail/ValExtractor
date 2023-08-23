package org.joda.time.chrono;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.joda.time.Chronology;
import org.joda.time.DateTimeField;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.DurationField;
import org.joda.time.IllegalFieldValueException;
import org.joda.time.Instant;
import org.joda.time.LocalDate;
import org.joda.time.ReadableInstant;
import org.joda.time.ReadablePartial;
import org.joda.time.field.BaseDateTimeField;
import org.joda.time.field.DecoratedDurationField;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

final public class GJChronology extends AssembledChronology  {
  final private static long serialVersionUID = -2545574827706931671L;
  final static Instant DEFAULT_CUTOVER = new Instant(-12219292800000L);
  final private static Map<DateTimeZone, ArrayList<GJChronology>> cCache = new HashMap<DateTimeZone, ArrayList<GJChronology>>();
  private JulianChronology iJulianChronology;
  private GregorianChronology iGregorianChronology;
  private Instant iCutoverInstant;
  private long iCutoverMillis;
  private long iGapDuration;
  private GJChronology(Chronology base, JulianChronology julian, GregorianChronology gregorian, Instant cutoverInstant) {
    super(base, new Object[]{ julian, gregorian, cutoverInstant } );
  }
  private GJChronology(JulianChronology julian, GregorianChronology gregorian, Instant cutoverInstant) {
    super(null, new Object[]{ julian, gregorian, cutoverInstant } );
  }
  public Chronology withUTC() {
    return withZone(DateTimeZone.UTC);
  }
  public Chronology withZone(DateTimeZone zone) {
    if(zone == null) {
      zone = DateTimeZone.getDefault();
    }
    if(zone == getZone()) {
      return this;
    }
    return getInstance(zone, iCutoverInstant, getMinimumDaysInFirstWeek());
  }
  public DateTimeZone getZone() {
    Chronology base;
    if((base = getBase()) != null) {
      return base.getZone();
    }
    return DateTimeZone.UTC;
  }
  public static GJChronology getInstance() {
    return getInstance(DateTimeZone.getDefault(), DEFAULT_CUTOVER, 4);
  }
  public static GJChronology getInstance(DateTimeZone zone) {
    return getInstance(zone, DEFAULT_CUTOVER, 4);
  }
  public static GJChronology getInstance(DateTimeZone zone, long gregorianCutover, int minDaysInFirstWeek) {
    Instant cutoverInstant;
    if(gregorianCutover == DEFAULT_CUTOVER.getMillis()) {
      cutoverInstant = null;
    }
    else {
      cutoverInstant = new Instant(gregorianCutover);
    }
    return getInstance(zone, cutoverInstant, minDaysInFirstWeek);
  }
  public static GJChronology getInstance(DateTimeZone zone, ReadableInstant gregorianCutover) {
    return getInstance(zone, gregorianCutover, 4);
  }
  public static synchronized GJChronology getInstance(DateTimeZone zone, ReadableInstant gregorianCutover, int minDaysInFirstWeek) {
    zone = DateTimeUtils.getZone(zone);
    Instant cutoverInstant;
    if(gregorianCutover == null) {
      cutoverInstant = DEFAULT_CUTOVER;
    }
    else {
      cutoverInstant = gregorianCutover.toInstant();
      LocalDate cutoverDate = new LocalDate(cutoverInstant.getMillis(), GregorianChronology.getInstance(zone));
      if(cutoverDate.getYear() <= 0) {
        throw new IllegalArgumentException("Cutover too early. Must be on or after 0001-01-01.");
      }
    }
    GJChronology chrono;
    synchronized(cCache) {
      ArrayList<GJChronology> chronos = cCache.get(zone);
      if(chronos == null) {
        chronos = new ArrayList<GJChronology>(2);
        cCache.put(zone, chronos);
      }
      else {
        for(int i = chronos.size(); --i >= 0; ) {
          chrono = chronos.get(i);
          if(minDaysInFirstWeek == chrono.getMinimumDaysInFirstWeek() && cutoverInstant.equals(chrono.getGregorianCutover())) {
            return chrono;
          }
        }
      }
      if(zone == DateTimeZone.UTC) {
        chrono = new GJChronology(JulianChronology.getInstance(zone, minDaysInFirstWeek), GregorianChronology.getInstance(zone, minDaysInFirstWeek), cutoverInstant);
      }
      else {
        chrono = getInstance(DateTimeZone.UTC, cutoverInstant, minDaysInFirstWeek);
        chrono = new GJChronology(ZonedChronology.getInstance(chrono, zone), chrono.iJulianChronology, chrono.iGregorianChronology, chrono.iCutoverInstant);
      }
      chronos.add(chrono);
    }
    return chrono;
  }
  public static GJChronology getInstanceUTC() {
    return getInstance(DateTimeZone.UTC, DEFAULT_CUTOVER, 4);
  }
  public Instant getGregorianCutover() {
    return iCutoverInstant;
  }
  private Object readResolve() {
    return getInstance(getZone(), iCutoverInstant, getMinimumDaysInFirstWeek());
  }
  public String toString() {
    StringBuffer sb = new StringBuffer(60);
    sb.append("GJChronology");
    sb.append('[');
    sb.append(getZone().getID());
    if(iCutoverMillis != DEFAULT_CUTOVER.getMillis()) {
      sb.append(",cutover=");
      DateTimeFormatter printer;
      if(withUTC().dayOfYear().remainder(iCutoverMillis) == 0) {
        printer = ISODateTimeFormat.date();
      }
      else {
        printer = ISODateTimeFormat.dateTime();
      }
      printer.withChronology(withUTC()).printTo(sb, iCutoverMillis);
    }
    if(getMinimumDaysInFirstWeek() != 4) {
      sb.append(",mdfw=");
      sb.append(getMinimumDaysInFirstWeek());
    }
    sb.append(']');
    return sb.toString();
  }
  public boolean equals(Object obj) {
    if(this == obj) {
      return true;
    }
    if(obj instanceof GJChronology) {
      GJChronology chrono = (GJChronology)obj;
      return iCutoverMillis == chrono.iCutoverMillis && getMinimumDaysInFirstWeek() == chrono.getMinimumDaysInFirstWeek() && getZone().equals(chrono.getZone());
    }
    return false;
  }
  public int getMinimumDaysInFirstWeek() {
    return iGregorianChronology.getMinimumDaysInFirstWeek();
  }
  public int hashCode() {
    return "GJ".hashCode() * 11 + getZone().hashCode() + getMinimumDaysInFirstWeek() + iCutoverInstant.hashCode();
  }
  private static long convertByWeekyear(final long instant, Chronology from, Chronology to) {
    long newInstant;
    newInstant = to.weekyear().set(0, from.weekyear().get(instant));
    newInstant = to.weekOfWeekyear().set(newInstant, from.weekOfWeekyear().get(instant));
    newInstant = to.dayOfWeek().set(newInstant, from.dayOfWeek().get(instant));
    newInstant = to.millisOfDay().set(newInstant, from.millisOfDay().get(instant));
    return newInstant;
  }
  private static long convertByYear(long instant, Chronology from, Chronology to) {
    return to.getDateTimeMillis(from.year().get(instant), from.monthOfYear().get(instant), from.dayOfMonth().get(instant), from.millisOfDay().get(instant));
  }
  public long getDateTimeMillis(int year, int monthOfYear, int dayOfMonth, int millisOfDay) throws IllegalArgumentException {
    Chronology base;
    if((base = getBase()) != null) {
      return base.getDateTimeMillis(year, monthOfYear, dayOfMonth, millisOfDay);
    }
    long instant = iGregorianChronology.getDateTimeMillis(year, monthOfYear, dayOfMonth, millisOfDay);
    if(instant < iCutoverMillis) {
      instant = iJulianChronology.getDateTimeMillis(year, monthOfYear, dayOfMonth, millisOfDay);
      if(instant >= iCutoverMillis) {
        throw new IllegalArgumentException("Specified date does not exist");
      }
    }
    return instant;
  }
  public long getDateTimeMillis(int year, int monthOfYear, int dayOfMonth, int hourOfDay, int minuteOfHour, int secondOfMinute, int millisOfSecond) throws IllegalArgumentException {
    Chronology base;
    if((base = getBase()) != null) {
      return base.getDateTimeMillis(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, millisOfSecond);
    }
    long instant;
    try {
      instant = iGregorianChronology.getDateTimeMillis(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, millisOfSecond);
    }
    catch (IllegalFieldValueException ex) {
      if(monthOfYear != 2 || dayOfMonth != 29) {
        throw ex;
      }
      instant = iGregorianChronology.getDateTimeMillis(year, monthOfYear, 28, hourOfDay, minuteOfHour, secondOfMinute, millisOfSecond);
      if(instant >= iCutoverMillis) {
        throw ex;
      }
    }
    if(instant < iCutoverMillis) {
      instant = iJulianChronology.getDateTimeMillis(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, millisOfSecond);
      if(instant >= iCutoverMillis) {
        throw new IllegalArgumentException("Specified date does not exist");
      }
    }
    return instant;
  }
  long gregorianToJulianByWeekyear(long instant) {
    return convertByWeekyear(instant, iGregorianChronology, iJulianChronology);
  }
  long gregorianToJulianByYear(long instant) {
    return convertByYear(instant, iGregorianChronology, iJulianChronology);
  }
  long julianToGregorianByWeekyear(long instant) {
    return convertByWeekyear(instant, iJulianChronology, iGregorianChronology);
  }
  long julianToGregorianByYear(long instant) {
    return convertByYear(instant, iJulianChronology, iGregorianChronology);
  }
  protected void assemble(Fields fields) {
    Object[] params = (Object[])getParam();
    JulianChronology julian = (JulianChronology)params[0];
    GregorianChronology gregorian = (GregorianChronology)params[1];
    Instant cutoverInstant = (Instant)params[2];
    iCutoverMillis = cutoverInstant.getMillis();
    iJulianChronology = julian;
    iGregorianChronology = gregorian;
    iCutoverInstant = cutoverInstant;
    if(getBase() != null) {
      return ;
    }
    if(julian.getMinimumDaysInFirstWeek() != gregorian.getMinimumDaysInFirstWeek()) {
      throw new IllegalArgumentException();
    }
    iGapDuration = iCutoverMillis - julianToGregorianByYear(iCutoverMillis);
    fields.copyFieldsFrom(gregorian);
    if(gregorian.millisOfDay().get(iCutoverMillis) == 0) {
      fields.millisOfSecond = new CutoverField(julian.millisOfSecond(), fields.millisOfSecond, iCutoverMillis);
      fields.millisOfDay = new CutoverField(julian.millisOfDay(), fields.millisOfDay, iCutoverMillis);
      fields.secondOfMinute = new CutoverField(julian.secondOfMinute(), fields.secondOfMinute, iCutoverMillis);
      fields.secondOfDay = new CutoverField(julian.secondOfDay(), fields.secondOfDay, iCutoverMillis);
      fields.minuteOfHour = new CutoverField(julian.minuteOfHour(), fields.minuteOfHour, iCutoverMillis);
      fields.minuteOfDay = new CutoverField(julian.minuteOfDay(), fields.minuteOfDay, iCutoverMillis);
      fields.hourOfDay = new CutoverField(julian.hourOfDay(), fields.hourOfDay, iCutoverMillis);
      fields.hourOfHalfday = new CutoverField(julian.hourOfHalfday(), fields.hourOfHalfday, iCutoverMillis);
      fields.clockhourOfDay = new CutoverField(julian.clockhourOfDay(), fields.clockhourOfDay, iCutoverMillis);
      fields.clockhourOfHalfday = new CutoverField(julian.clockhourOfHalfday(), fields.clockhourOfHalfday, iCutoverMillis);
      fields.halfdayOfDay = new CutoverField(julian.halfdayOfDay(), fields.halfdayOfDay, iCutoverMillis);
    }
    {
      fields.era = new CutoverField(julian.era(), fields.era, iCutoverMillis);
    }
    {
      fields.year = new ImpreciseCutoverField(julian.year(), fields.year, iCutoverMillis);
      fields.years = fields.year.getDurationField();
      fields.yearOfEra = new ImpreciseCutoverField(julian.yearOfEra(), fields.yearOfEra, fields.years, iCutoverMillis);
      fields.centuryOfEra = new ImpreciseCutoverField(julian.centuryOfEra(), fields.centuryOfEra, iCutoverMillis);
      fields.centuries = fields.centuryOfEra.getDurationField();
      fields.yearOfCentury = new ImpreciseCutoverField(julian.yearOfCentury(), fields.yearOfCentury, fields.years, fields.centuries, iCutoverMillis);
      fields.monthOfYear = new ImpreciseCutoverField(julian.monthOfYear(), fields.monthOfYear, null, fields.years, iCutoverMillis);
      fields.months = fields.monthOfYear.getDurationField();
      fields.weekyear = new ImpreciseCutoverField(julian.weekyear(), fields.weekyear, null, iCutoverMillis, true);
      fields.weekyears = fields.weekyear.getDurationField();
      fields.weekyearOfCentury = new ImpreciseCutoverField(julian.weekyearOfCentury(), fields.weekyearOfCentury, fields.weekyears, fields.centuries, iCutoverMillis);
    }
    {
      long cutover = gregorian.year().roundCeiling(iCutoverMillis);
      fields.dayOfYear = new CutoverField(julian.dayOfYear(), fields.dayOfYear, fields.years, cutover, false);
    }
    {
      long cutover = gregorian.weekyear().roundCeiling(iCutoverMillis);
      fields.weekOfWeekyear = new CutoverField(julian.weekOfWeekyear(), fields.weekOfWeekyear, fields.weekyears, cutover, true);
    }
    {
      CutoverField cf = new CutoverField(julian.dayOfMonth(), fields.dayOfMonth, iCutoverMillis);
      cf.iRangeDurationField = fields.months;
      fields.dayOfMonth = cf;
    }
  }
  
  private class CutoverField extends BaseDateTimeField  {
    @SuppressWarnings(value = {"unused", }) final private static long serialVersionUID = 3528501219481026402L;
    final DateTimeField iJulianField;
    final DateTimeField iGregorianField;
    final long iCutover;
    final boolean iConvertByWeekyear;
    protected DurationField iDurationField;
    protected DurationField iRangeDurationField;
    CutoverField(DateTimeField julianField, DateTimeField gregorianField, DurationField rangeField, long cutoverMillis, boolean convertByWeekyear) {
      super(gregorianField.getType());
      iJulianField = julianField;
      iGregorianField = gregorianField;
      iCutover = cutoverMillis;
      iConvertByWeekyear = convertByWeekyear;
      iDurationField = gregorianField.getDurationField();
      if(rangeField == null) {
        rangeField = gregorianField.getRangeDurationField();
        if(rangeField == null) {
          rangeField = julianField.getRangeDurationField();
        }
      }
      iRangeDurationField = rangeField;
    }
    CutoverField(DateTimeField julianField, DateTimeField gregorianField, long cutoverMillis) {
      this(julianField, gregorianField, cutoverMillis, false);
    }
    CutoverField(DateTimeField julianField, DateTimeField gregorianField, long cutoverMillis, boolean convertByWeekyear) {
      this(julianField, gregorianField, null, cutoverMillis, convertByWeekyear);
    }
    public DurationField getDurationField() {
      return iDurationField;
    }
    public DurationField getLeapDurationField() {
      return iGregorianField.getLeapDurationField();
    }
    public DurationField getRangeDurationField() {
      return iRangeDurationField;
    }
    public String getAsShortText(int fieldValue, Locale locale) {
      return iGregorianField.getAsShortText(fieldValue, locale);
    }
    public String getAsShortText(long instant, Locale locale) {
      if(instant >= iCutover) {
        return iGregorianField.getAsShortText(instant, locale);
      }
      else {
        return iJulianField.getAsShortText(instant, locale);
      }
    }
    public String getAsText(int fieldValue, Locale locale) {
      return iGregorianField.getAsText(fieldValue, locale);
    }
    public String getAsText(long instant, Locale locale) {
      if(instant >= iCutover) {
        return iGregorianField.getAsText(instant, locale);
      }
      else {
        return iJulianField.getAsText(instant, locale);
      }
    }
    public boolean isLeap(long instant) {
      if(instant >= iCutover) {
        return iGregorianField.isLeap(instant);
      }
      else {
        return iJulianField.isLeap(instant);
      }
    }
    public boolean isLenient() {
      return false;
    }
    public int get(long instant) {
      if(instant >= iCutover) {
        return iGregorianField.get(instant);
      }
      else {
        return iJulianField.get(instant);
      }
    }
    public int getDifference(long minuendInstant, long subtrahendInstant) {
      return iGregorianField.getDifference(minuendInstant, subtrahendInstant);
    }
    public int getLeapAmount(long instant) {
      if(instant >= iCutover) {
        return iGregorianField.getLeapAmount(instant);
      }
      else {
        return iJulianField.getLeapAmount(instant);
      }
    }
    public int getMaximumShortTextLength(Locale locale) {
      return Math.max(iJulianField.getMaximumShortTextLength(locale), iGregorianField.getMaximumShortTextLength(locale));
    }
    public int getMaximumTextLength(Locale locale) {
      return Math.max(iJulianField.getMaximumTextLength(locale), iGregorianField.getMaximumTextLength(locale));
    }
    public int getMaximumValue() {
      return iGregorianField.getMaximumValue();
    }
    public int getMaximumValue(long instant) {
      if(instant >= iCutover) {
        return iGregorianField.getMaximumValue(instant);
      }
      int max = iJulianField.getMaximumValue(instant);
      instant = iJulianField.set(instant, max);
      if(instant >= iCutover) {
        max = iJulianField.get(iJulianField.add(iCutover, -1));
      }
      return max;
    }
    public int getMaximumValue(ReadablePartial partial) {
      long instant = GJChronology.getInstanceUTC().set(partial, 0L);
      return getMaximumValue(instant);
    }
    public int getMaximumValue(ReadablePartial partial, int[] values) {
      Chronology chrono = GJChronology.getInstanceUTC();
      long instant = 0L;
      for(int i = 0, isize = partial.size(); i < isize; i++) {
        DateTimeField field = partial.getFieldType(i).getField(chrono);
        if(values[i] <= field.getMaximumValue(instant)) {
          instant = field.set(instant, values[i]);
        }
      }
      return getMaximumValue(instant);
    }
    public int getMinimumValue() {
      return iJulianField.getMinimumValue();
    }
    public int getMinimumValue(long instant) {
      if(instant < iCutover) {
        return iJulianField.getMinimumValue(instant);
      }
      int min = iGregorianField.getMinimumValue(instant);
      instant = iGregorianField.set(instant, min);
      if(instant < iCutover) {
        min = iGregorianField.get(iCutover);
      }
      return min;
    }
    public int getMinimumValue(ReadablePartial partial) {
      return iJulianField.getMinimumValue(partial);
    }
    public int getMinimumValue(ReadablePartial partial, int[] values) {
      return iJulianField.getMinimumValue(partial, values);
    }
    public int[] add(ReadablePartial partial, int fieldIndex, int[] values, int valueToAdd) {
      if(valueToAdd == 0) {
        return values;
      }
      if(DateTimeUtils.isContiguous(partial)) {
        long instant = 0L;
        for(int i = 0, isize = partial.size(); i < isize; i++) {
          instant = partial.getFieldType(i).getField(GJChronology.this).set(instant, values[i]);
        }
        instant = add(instant, valueToAdd);
        return GJChronology.this.get(partial, instant);
      }
      else {
        return super.add(partial, fieldIndex, values, valueToAdd);
      }
    }
    public long add(long instant, int value) {
      return iGregorianField.add(instant, value);
    }
    public long add(long instant, long value) {
      return iGregorianField.add(instant, value);
    }
    public long getDifferenceAsLong(long minuendInstant, long subtrahendInstant) {
      return iGregorianField.getDifferenceAsLong(minuendInstant, subtrahendInstant);
    }
    protected long gregorianToJulian(long instant) {
      if(iConvertByWeekyear) {
        return gregorianToJulianByWeekyear(instant);
      }
      else {
        return gregorianToJulianByYear(instant);
      }
    }
    protected long julianToGregorian(long instant) {
      if(iConvertByWeekyear) {
        return julianToGregorianByWeekyear(instant);
      }
      else {
        return julianToGregorianByYear(instant);
      }
    }
    public long roundCeiling(long instant) {
      if(instant >= iCutover) {
        instant = iGregorianField.roundCeiling(instant);
      }
      else {
        instant = iJulianField.roundCeiling(instant);
        if(instant >= iCutover) {
          if(instant - iGapDuration >= iCutover) {
            instant = julianToGregorian(instant);
          }
        }
      }
      return instant;
    }
    public long roundFloor(long instant) {
      if(instant >= iCutover) {
        instant = iGregorianField.roundFloor(instant);
        if(instant < iCutover) {
          if(instant + iGapDuration < iCutover) {
            instant = gregorianToJulian(instant);
          }
        }
      }
      else {
        instant = iJulianField.roundFloor(instant);
      }
      return instant;
    }
    public long set(long instant, int value) {
      if(instant >= iCutover) {
        instant = iGregorianField.set(instant, value);
        if(instant < iCutover) {
          if(instant + iGapDuration < iCutover) {
            instant = gregorianToJulian(instant);
          }
          if(get(instant) != value) {
            throw new IllegalFieldValueException(iGregorianField.getType(), Integer.valueOf(value), null, null);
          }
        }
      }
      else {
        instant = iJulianField.set(instant, value);
        if(instant >= iCutover) {
          if(instant - iGapDuration >= iCutover) {
            instant = julianToGregorian(instant);
          }
          if(get(instant) != value) {
            throw new IllegalFieldValueException(iJulianField.getType(), Integer.valueOf(value), null, null);
          }
        }
      }
      return instant;
    }
    public long set(long instant, String text, Locale locale) {
      if(instant >= iCutover) {
        instant = iGregorianField.set(instant, text, locale);
        if(instant < iCutover) {
          if(instant + iGapDuration < iCutover) {
            instant = gregorianToJulian(instant);
          }
        }
      }
      else {
        instant = iJulianField.set(instant, text, locale);
        if(instant >= iCutover) {
          if(instant - iGapDuration >= iCutover) {
            instant = julianToGregorian(instant);
          }
        }
      }
      return instant;
    }
  }
  
  final private class ImpreciseCutoverField extends CutoverField  {
    @SuppressWarnings(value = {"unused", }) final private static long serialVersionUID = 3410248757173576441L;
    ImpreciseCutoverField(DateTimeField julianField, DateTimeField gregorianField, DurationField durationField, DurationField rangeDurationField, long cutoverMillis) {
      this(julianField, gregorianField, durationField, cutoverMillis, false);
      iRangeDurationField = rangeDurationField;
    }
    ImpreciseCutoverField(DateTimeField julianField, DateTimeField gregorianField, DurationField durationField, long cutoverMillis) {
      this(julianField, gregorianField, durationField, cutoverMillis, false);
    }
    ImpreciseCutoverField(DateTimeField julianField, DateTimeField gregorianField, DurationField durationField, long cutoverMillis, boolean convertByWeekyear) {
      super(julianField, gregorianField, cutoverMillis, convertByWeekyear);
      if(durationField == null) {
        durationField = new LinkedDurationField(iDurationField, this);
      }
      iDurationField = durationField;
    }
    ImpreciseCutoverField(DateTimeField julianField, DateTimeField gregorianField, long cutoverMillis) {
      this(julianField, gregorianField, null, cutoverMillis, false);
    }
    public int getDifference(long minuendInstant, long subtrahendInstant) {
      if(minuendInstant >= iCutover) {
        if(subtrahendInstant >= iCutover) {
          return iGregorianField.getDifference(minuendInstant, subtrahendInstant);
        }
        minuendInstant = gregorianToJulian(minuendInstant);
        int var_258 = iJulianField.getDifference(minuendInstant, subtrahendInstant);
        return var_258;
      }
      else {
        if(subtrahendInstant < iCutover) {
          return iJulianField.getDifference(minuendInstant, subtrahendInstant);
        }
        minuendInstant = julianToGregorian(minuendInstant);
        return iGregorianField.getDifference(minuendInstant, subtrahendInstant);
      }
    }
    public int getMaximumValue(long instant) {
      if(instant >= iCutover) {
        return iGregorianField.getMaximumValue(instant);
      }
      else {
        return iJulianField.getMaximumValue(instant);
      }
    }
    public int getMinimumValue(long instant) {
      if(instant >= iCutover) {
        return iGregorianField.getMinimumValue(instant);
      }
      else {
        return iJulianField.getMinimumValue(instant);
      }
    }
    public long add(long instant, int value) {
      if(instant >= iCutover) {
        instant = iGregorianField.add(instant, value);
        if(instant < iCutover) {
          if(instant + iGapDuration < iCutover) {
            if(iConvertByWeekyear) {
              int wyear = iGregorianChronology.weekyear().get(instant);
              if(wyear <= 0) {
                instant = iGregorianChronology.weekyear().add(instant, -1);
              }
            }
            else {
              int year = iGregorianChronology.year().get(instant);
              if(year <= 0) {
                instant = iGregorianChronology.year().add(instant, -1);
              }
            }
            instant = gregorianToJulian(instant);
          }
        }
      }
      else {
        instant = iJulianField.add(instant, value);
        if(instant >= iCutover) {
          if(instant - iGapDuration >= iCutover) {
            instant = julianToGregorian(instant);
          }
        }
      }
      return instant;
    }
    public long add(long instant, long value) {
      if(instant >= iCutover) {
        instant = iGregorianField.add(instant, value);
        if(instant < iCutover) {
          if(instant + iGapDuration < iCutover) {
            if(iConvertByWeekyear) {
              int wyear = iGregorianChronology.weekyear().get(instant);
              if(wyear <= 0) {
                instant = iGregorianChronology.weekyear().add(instant, -1);
              }
            }
            else {
              int year = iGregorianChronology.year().get(instant);
              if(year <= 0) {
                instant = iGregorianChronology.year().add(instant, -1);
              }
            }
            instant = gregorianToJulian(instant);
          }
        }
      }
      else {
        instant = iJulianField.add(instant, value);
        if(instant >= iCutover) {
          if(instant - iGapDuration >= iCutover) {
            instant = julianToGregorian(instant);
          }
        }
      }
      return instant;
    }
    public long getDifferenceAsLong(long minuendInstant, long subtrahendInstant) {
      if(minuendInstant >= iCutover) {
        if(subtrahendInstant >= iCutover) {
          return iGregorianField.getDifferenceAsLong(minuendInstant, subtrahendInstant);
        }
        minuendInstant = gregorianToJulian(minuendInstant);
        return iJulianField.getDifferenceAsLong(minuendInstant, subtrahendInstant);
      }
      else {
        if(subtrahendInstant < iCutover) {
          return iJulianField.getDifferenceAsLong(minuendInstant, subtrahendInstant);
        }
        minuendInstant = julianToGregorian(minuendInstant);
        return iGregorianField.getDifferenceAsLong(minuendInstant, subtrahendInstant);
      }
    }
  }
  
  private static class LinkedDurationField extends DecoratedDurationField  {
    final private static long serialVersionUID = 4097975388007713084L;
    final private ImpreciseCutoverField iField;
    LinkedDurationField(DurationField durationField, ImpreciseCutoverField dateTimeField) {
      super(durationField, durationField.getType());
      iField = dateTimeField;
    }
    public int getDifference(long minuendInstant, long subtrahendInstant) {
      return iField.getDifference(minuendInstant, subtrahendInstant);
    }
    public long add(long instant, int value) {
      return iField.add(instant, value);
    }
    public long add(long instant, long value) {
      return iField.add(instant, value);
    }
    public long getDifferenceAsLong(long minuendInstant, long subtrahendInstant) {
      return iField.getDifferenceAsLong(minuendInstant, subtrahendInstant);
    }
  }
}