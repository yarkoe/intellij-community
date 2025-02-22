class NotRecord {
  public <error descr="Parameter list expected">NotRecord</error> {
    
  }
}
record NonPublic(int x) {
  <error descr="Compact constructor must be 'public'">NonPublic</error> {
    
  }
}
record Throws(int x) {
  public Throws<error descr="Identifier expected"> </error><error descr="Unexpected token">throws</error> <error descr="Invalid method declaration; return type required">Throwable</error> {}
}
record Generic() {
  public <error descr="Canonical constructor cannot have type parameters"><T></error> Generic() {}
}
record Delegate(int x) {
  public Delegate {
    <error descr="Canonical constructor cannot delegate to another constructor">this("")</error>;
  }
  <error descr="Non-canonical record constructor must delegate to another constructor">Delegate</error>(String s) {
    
  }
}
record ReturnInCompact(int x) {
  public ReturnInCompact {
    if (Math.random() > 0.5) <error descr="'return' statement is not allowed in compact constructor">return;</error>
  }
}
record NotInitialized(int x, 
                      int <error descr="Record component 'y' might not be initialized in canonical constructor">y</error>, 
                      int z) {
  public NotInitialized {
    this.x = 0;
    if (Math.random() > 0.5) this.y = 1;
  }
}
record TwoCompacts(int x, int y) {
  <error descr="'TwoCompacts()' is already defined in 'TwoCompacts'">public TwoCompacts </error>{}
  <error descr="'TwoCompacts()' is already defined in 'TwoCompacts'">public TwoCompacts </error>{}
}
record CompactAndCanonical(int x, int y) {
  // TODO
  public CompactAndCanonical(int x, int y) {
    this.x = x;
    this.y = y;
  }
  public CompactAndCanonical {
    
  }
}