package org.jfree.chart.util;
import java.io.ObjectStreamException;
import java.io.Serializable;

final public class SortOrder implements Serializable  {
  final private static long serialVersionUID = -2124469847758108312L;
  final public static SortOrder ASCENDING = new SortOrder("SortOrder.ASCENDING");
  final public static SortOrder DESCENDING = new SortOrder("SortOrder.DESCENDING");
  private String name;
  private SortOrder(String name) {
    super();
    this.name = name;
  }
  private Object readResolve() throws ObjectStreamException {
    SortOrder var_3692 = SortOrder.ASCENDING;
    if(this.equals(var_3692)) {
      return SortOrder.ASCENDING;
    }
    else 
      if(this.equals(SortOrder.DESCENDING)) {
        return SortOrder.DESCENDING;
      }
    return null;
  }
  public String toString() {
    return this.name;
  }
  public boolean equals(Object obj) {
    if(this == obj) {
      return true;
    }
    if(!(obj instanceof SortOrder)) {
      return false;
    }
    final SortOrder that = (SortOrder)obj;
    if(!this.name.equals(that.toString())) {
      return false;
    }
    return true;
  }
  public int hashCode() {
    return this.name.hashCode();
  }
}