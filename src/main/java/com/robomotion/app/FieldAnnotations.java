package com.robomotion.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FieldAnnotations {

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Title {
		String title() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Description {
		String description() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface MessageScope {
		boolean messageScope() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface MessageOnly {
		boolean messageOnly() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface CustomScope {
		boolean customScope() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Input {
		boolean input() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Output {
		boolean output() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Option {
		boolean option() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Hidden {
		boolean hidden() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Default {
		String scope() default "";

		String name() default "";

		Class<?> cls() default String.class;

		String value() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Enum {
		String enumeration() default "";

		String enumNames() default "";
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Category {
		ECategory category() default ECategory.Null;
	}

	public enum ECategory {
		Null(0), Login(1), Email(2), CreditCard(3), Token(4), Database(5), Document(6);

		private final int category;

		ECategory(final int category) {
			this.category = category;
		}

		public int getCategory() {
			return this.category;
		}
	}
}