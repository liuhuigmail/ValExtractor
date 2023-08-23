package org.apache.commons.math3.fraction;
import java.io.Serializable;
import java.math.BigInteger;
import org.apache.commons.math3.FieldElement;
import org.apache.commons.math3.exception.util.LocalizedFormats;
import org.apache.commons.math3.exception.MathArithmeticException;
import org.apache.commons.math3.exception.NullArgumentException;
import org.apache.commons.math3.util.ArithmeticUtils;
import org.apache.commons.math3.util.FastMath;

public class Fraction extends Number implements FieldElement<Fraction>, Comparable<Fraction>, Serializable  {
  final public static Fraction TWO = new Fraction(2, 1);
  final public static Fraction ONE = new Fraction(1, 1);
  final public static Fraction ZERO = new Fraction(0, 1);
  final public static Fraction FOUR_FIFTHS = new Fraction(4, 5);
  final public static Fraction ONE_FIFTH = new Fraction(1, 5);
  final public static Fraction ONE_HALF = new Fraction(1, 2);
  final public static Fraction ONE_QUARTER = new Fraction(1, 4);
  final public static Fraction ONE_THIRD = new Fraction(1, 3);
  final public static Fraction THREE_FIFTHS = new Fraction(3, 5);
  final public static Fraction THREE_QUARTERS = new Fraction(3, 4);
  final public static Fraction TWO_FIFTHS = new Fraction(2, 5);
  final public static Fraction TWO_QUARTERS = new Fraction(2, 4);
  final public static Fraction TWO_THIRDS = new Fraction(2, 3);
  final public static Fraction MINUS_ONE = new Fraction(-1, 1);
  final private static long serialVersionUID = 3698073679419233275L;
  final private static double DEFAULT_EPSILON = 1e-5D;
  final private int denominator;
  final private int numerator;
  public Fraction(double value) throws FractionConversionException {
    this(value, DEFAULT_EPSILON, 100);
  }
  private Fraction(double value, double epsilon, int maxDenominator, int maxIterations) throws FractionConversionException {
    super();
    long overflow = Integer.MAX_VALUE;
    double r0 = value;
    long a0 = (long)FastMath.floor(r0);
    if(FastMath.abs(a0) > overflow) {
      throw new FractionConversionException(value, a0, 1L);
    }
    if(FastMath.abs(a0 - value) < epsilon) {
      this.numerator = (int)a0;
      this.denominator = 1;
      return ;
    }
    long p0 = 1;
    long q0 = 0;
    long p1 = a0;
    long q1 = 1;
    long p2 = 0;
    long q2 = 1;
    int n = 0;
    boolean stop = false;
    do {
      ++n;
      double r1 = 1.0D / (r0 - a0);
      long a1 = (long)FastMath.floor(r1);
      p2 = (a1 * p1) + p0;
      q2 = (a1 * q1) + q0;
      if((FastMath.abs(p2) > overflow) || (FastMath.abs(q2) > overflow)) {
        if(epsilon == 0.0D && FastMath.abs(q1) < maxDenominator) {
          break ;
        }
        throw new FractionConversionException(value, p2, q2);
      }
      double convergent = (double)p2 / (double)q2;
      if(n < maxIterations && FastMath.abs(convergent - value) > epsilon && q2 < maxDenominator) {
        p0 = p1;
        p1 = p2;
        q0 = q1;
        q1 = q2;
        a0 = a1;
        r0 = r1;
      }
      else {
        stop = true;
      }
    }while(!stop);
    if(n >= maxIterations) {
      throw new FractionConversionException(value, maxIterations);
    }
    if(q2 < maxDenominator) {
      this.numerator = (int)p2;
      this.denominator = (int)q2;
    }
    else {
      this.numerator = (int)p1;
      this.denominator = (int)q1;
    }
  }
  public Fraction(double value, double epsilon, int maxIterations) throws FractionConversionException {
    this(value, epsilon, Integer.MAX_VALUE, maxIterations);
  }
  public Fraction(double value, int maxDenominator) throws FractionConversionException {
    this(value, 0, maxDenominator, 100);
  }
  public Fraction(int num) {
    this(num, 1);
  }
  public Fraction(int num, int den) {
    super();
    if(den == 0) {
      throw new MathArithmeticException(LocalizedFormats.ZERO_DENOMINATOR_IN_FRACTION, num, den);
    }
    if(den < 0) {
      if(num == Integer.MIN_VALUE || den == Integer.MIN_VALUE) {
        throw new MathArithmeticException(LocalizedFormats.OVERFLOW_IN_FRACTION, num, den);
      }
      num = -num;
      den = -den;
    }
    final int d = ArithmeticUtils.gcd(num, den);
    if(d > 1) {
      num /= d;
      den /= d;
    }
    if(den < 0) {
      num = -num;
      den = -den;
    }
    this.numerator = num;
    this.denominator = den;
  }
  public Fraction abs() {
    Fraction ret;
    if(numerator >= 0) {
      ret = this;
    }
    else {
      ret = negate();
    }
    return ret;
  }
  public Fraction add(final int i) {
    return new Fraction(numerator + i * denominator, denominator);
  }
  public Fraction add(Fraction fraction) {
    return addSub(fraction, true);
  }
  private Fraction addSub(Fraction fraction, boolean isAdd) {
    if(fraction == null) {
      throw new NullArgumentException(LocalizedFormats.FRACTION);
    }
    if(numerator == 0) {
      return isAdd ? fraction : fraction.negate();
    }
    if(fraction.numerator == 0) {
      return this;
    }
    int d1 = ArithmeticUtils.gcd(denominator, fraction.denominator);
    if(d1 == 1) {
      int uvp = ArithmeticUtils.mulAndCheck(numerator, fraction.denominator);
      int var_1079 = fraction.numerator;
      int upv = ArithmeticUtils.mulAndCheck(var_1079, denominator);
      return new Fraction(isAdd ? ArithmeticUtils.addAndCheck(uvp, upv) : ArithmeticUtils.subAndCheck(uvp, upv), ArithmeticUtils.mulAndCheck(denominator, fraction.denominator));
    }
    BigInteger uvp = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(fraction.denominator / d1));
    BigInteger upv = BigInteger.valueOf(fraction.numerator).multiply(BigInteger.valueOf(denominator / d1));
    BigInteger t = isAdd ? uvp.add(upv) : uvp.subtract(upv);
    int tmodd1 = t.mod(BigInteger.valueOf(d1)).intValue();
    int d2 = (tmodd1 == 0) ? d1 : ArithmeticUtils.gcd(tmodd1, d1);
    BigInteger w = t.divide(BigInteger.valueOf(d2));
    if(w.bitLength() > 31) {
      throw new MathArithmeticException(LocalizedFormats.NUMERATOR_OVERFLOW_AFTER_MULTIPLY, w);
    }
    return new Fraction(w.intValue(), ArithmeticUtils.mulAndCheck(denominator / d1, fraction.denominator / d2));
  }
  public Fraction divide(final int i) {
    return new Fraction(numerator, denominator * i);
  }
  public Fraction divide(Fraction fraction) {
    if(fraction == null) {
      throw new NullArgumentException(LocalizedFormats.FRACTION);
    }
    if(fraction.numerator == 0) {
      throw new MathArithmeticException(LocalizedFormats.ZERO_FRACTION_TO_DIVIDE_BY, fraction.numerator, fraction.denominator);
    }
    return multiply(fraction.reciprocal());
  }
  public static Fraction getReducedFraction(int numerator, int denominator) {
    if(denominator == 0) {
      throw new MathArithmeticException(LocalizedFormats.ZERO_DENOMINATOR_IN_FRACTION, numerator, denominator);
    }
    if(numerator == 0) {
      return ZERO;
    }
    if(denominator == Integer.MIN_VALUE && (numerator & 1) == 0) {
      numerator /= 2;
      denominator /= 2;
    }
    if(denominator < 0) {
      if(numerator == Integer.MIN_VALUE || denominator == Integer.MIN_VALUE) {
        throw new MathArithmeticException(LocalizedFormats.OVERFLOW_IN_FRACTION, numerator, denominator);
      }
      numerator = -numerator;
      denominator = -denominator;
    }
    int gcd = ArithmeticUtils.gcd(numerator, denominator);
    numerator /= gcd;
    denominator /= gcd;
    return new Fraction(numerator, denominator);
  }
  public Fraction multiply(final int i) {
    return new Fraction(numerator * i, denominator);
  }
  public Fraction multiply(Fraction fraction) {
    if(fraction == null) {
      throw new NullArgumentException(LocalizedFormats.FRACTION);
    }
    if(numerator == 0 || fraction.numerator == 0) {
      return ZERO;
    }
    int d1 = ArithmeticUtils.gcd(numerator, fraction.denominator);
    int d2 = ArithmeticUtils.gcd(fraction.numerator, denominator);
    return getReducedFraction(ArithmeticUtils.mulAndCheck(numerator / d1, fraction.numerator / d2), ArithmeticUtils.mulAndCheck(denominator / d2, fraction.denominator / d1));
  }
  public Fraction negate() {
    if(numerator == Integer.MIN_VALUE) {
      throw new MathArithmeticException(LocalizedFormats.OVERFLOW_IN_FRACTION, numerator, denominator);
    }
    return new Fraction(-numerator, denominator);
  }
  public Fraction reciprocal() {
    return new Fraction(denominator, numerator);
  }
  public Fraction subtract(final int i) {
    return new Fraction(numerator - i * denominator, denominator);
  }
  public Fraction subtract(Fraction fraction) {
    return addSub(fraction, false);
  }
  public FractionField getField() {
    return FractionField.getInstance();
  }
  @Override() public String toString() {
    String str = null;
    if(denominator == 1) {
      str = Integer.toString(numerator);
    }
    else 
      if(numerator == 0) {
        str = "0";
      }
      else {
        str = numerator + " / " + denominator;
      }
    return str;
  }
  @Override() public boolean equals(Object other) {
    if(this == other) {
      return true;
    }
    if(other instanceof Fraction) {
      Fraction rhs = (Fraction)other;
      return (numerator == rhs.numerator) && (denominator == rhs.denominator);
    }
    return false;
  }
  @Override() public double doubleValue() {
    return (double)numerator / (double)denominator;
  }
  public double percentageValue() {
    return 100 * doubleValue();
  }
  @Override() public float floatValue() {
    return (float)doubleValue();
  }
  public int compareTo(Fraction object) {
    long nOd = ((long)numerator) * object.denominator;
    long dOn = ((long)denominator) * object.numerator;
    return (nOd < dOn) ? -1 : ((nOd > dOn) ? +1 : 0);
  }
  public int getDenominator() {
    return denominator;
  }
  public int getNumerator() {
    return numerator;
  }
  @Override() public int hashCode() {
    return 37 * (37 * 17 + numerator) + denominator;
  }
  @Override() public int intValue() {
    return (int)doubleValue();
  }
  @Override() public long longValue() {
    return (long)doubleValue();
  }
}