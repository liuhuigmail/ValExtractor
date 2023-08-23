package org.apache.commons.math3.optim.univariate;
import org.apache.commons.math3.util.Precision;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.exception.NumberIsTooSmallException;
import org.apache.commons.math3.exception.NotStrictlyPositiveException;
import org.apache.commons.math3.optim.ConvergenceChecker;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

public class BrentOptimizer extends UnivariateOptimizer  {
  final private static double GOLDEN_SECTION = 0.5D * (3 - FastMath.sqrt(5));
  final private static double MIN_RELATIVE_TOLERANCE = 2 * FastMath.ulp(1D);
  final private double relativeThreshold;
  final private double absoluteThreshold;
  public BrentOptimizer(double rel, double abs) {
    this(rel, abs, null);
  }
  public BrentOptimizer(double rel, double abs, ConvergenceChecker<UnivariatePointValuePair> checker) {
    super(checker);
    if(rel < MIN_RELATIVE_TOLERANCE) {
      throw new NumberIsTooSmallException(rel, MIN_RELATIVE_TOLERANCE, true);
    }
    if(abs <= 0) {
      throw new NotStrictlyPositiveException(abs);
    }
    relativeThreshold = rel;
    absoluteThreshold = abs;
  }
  private UnivariatePointValuePair best(UnivariatePointValuePair a, UnivariatePointValuePair b, boolean isMinim) {
    if(a == null) {
      return b;
    }
    if(b == null) {
      return a;
    }
    if(isMinim) {
      return a.getValue() <= b.getValue() ? a : b;
    }
    else {
      return a.getValue() >= b.getValue() ? a : b;
    }
  }
  @Override() protected UnivariatePointValuePair doOptimize() {
    final boolean isMinim = getGoalType() == GoalType.MINIMIZE;
    final double lo = getMin();
    final double mid = getStartValue();
    final double hi = getMax();
    final ConvergenceChecker<UnivariatePointValuePair> checker = getConvergenceChecker();
    double a;
    double b;
    if(lo < hi) {
      a = lo;
      b = hi;
    }
    else {
      a = hi;
      b = lo;
    }
    double x = mid;
    double v = x;
    double w = x;
    double d = 0;
    double e = 0;
    double fx = computeObjectiveValue(x);
    if(!isMinim) {
      fx = -fx;
    }
    double fv = fx;
    double fw = fx;
    UnivariatePointValuePair previous = null;
    UnivariatePointValuePair current = new UnivariatePointValuePair(x, isMinim ? fx : -fx);
    UnivariatePointValuePair best = current;
    int iter = 0;
    while(true){
      final double m = 0.5D * (a + b);
      final double tol1 = relativeThreshold * FastMath.abs(x) + absoluteThreshold;
      final double tol2 = 2 * tol1;
      final boolean stop = FastMath.abs(x - m) <= tol2 - 0.5D * (b - a);
      if(!stop) {
        double p = 0;
        double q = 0;
        double r = 0;
        double u = 0;
        if(FastMath.abs(e) > tol1) {
          r = (x - w) * (fx - fv);
          q = (x - v) * (fx - fw);
          p = (x - v) * q - (x - w) * r;
          q = 2 * (q - r);
          if(q > 0) {
            p = -p;
          }
          else {
            q = -q;
          }
          r = e;
          e = d;
          if(p > q * (a - x) && p < q * (b - x) && FastMath.abs(p) < FastMath.abs(0.5D * q * r)) {
            d = p / q;
            u = x + d;
            if(u - a < tol2 || b - u < tol2) {
              if(x <= m) {
                d = tol1;
              }
              else {
                d = -tol1;
              }
            }
          }
          else {
            if(x < m) {
              e = b - x;
            }
            else {
              e = a - x;
            }
            d = GOLDEN_SECTION * e;
          }
        }
        else {
          if(x < m) {
            e = b - x;
          }
          else {
            e = a - x;
          }
          d = GOLDEN_SECTION * e;
        }
        if(FastMath.abs(d) < tol1) {
          if(d >= 0) {
            u = x + tol1;
          }
          else {
            u = x - tol1;
          }
        }
        else {
          u = x + d;
        }
        double fu = computeObjectiveValue(u);
        if(!isMinim) {
          fu = -fu;
        }
        previous = current;
        current = new UnivariatePointValuePair(u, isMinim ? fu : -fu);
        UnivariatePointValuePair var_3235 = best(previous, current, isMinim);
        best = best(best, var_3235, isMinim);
        if(checker != null && checker.converged(iter, previous, current)) {
          return best;
        }
        if(fu <= fx) {
          if(u < x) {
            b = x;
          }
          else {
            a = x;
          }
          v = w;
          fv = fw;
          w = x;
          fw = fx;
          x = u;
          fx = fu;
        }
        else {
          if(u < x) {
            a = u;
          }
          else {
            b = u;
          }
          if(fu <= fw || Precision.equals(w, x)) {
            v = w;
            fv = fw;
            w = u;
            fw = fu;
          }
          else 
            if(fu <= fv || Precision.equals(v, x) || Precision.equals(v, w)) {
              v = u;
              fv = fu;
            }
        }
      }
      else {
        return best(best, best(previous, current, isMinim), isMinim);
      }
      ++iter;
    }
  }
}