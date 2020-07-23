package foo;

public abstract class Modifiers {

	public int i1;
	protected int i2;
	private int i3;
	static int i4;
	abstract int i5();
	final int i6;
	native int i7();
	synchronized int i8() {}
	transient int i9;
	volatile int i10;
	strictfp int i11() {}

	interface SomeInterface {
		default int i12() {}
	}

}
