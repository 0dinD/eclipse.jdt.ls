package org.sample;

/**
 * This is Bar
 */
public class Bar {

	public static void main(String[] args) {
      System.out.print( "Hello world! from "+Bar.class);
	}
	
	@Deprecated
	public static interface MyInterface {
		
		void foo();
	}
	
	public static class MyClass {
		
		void bar() {}
	}
	
	public static final String EMPTY = "";
	
	
	public enum Foo {
		Bar, Zoo
	}
}
