package backport;

public final class CharacterBackportJava11Main {
  public static void main(String[] args) {
    testToStringCodepoint();
  }

  private static void testToStringCodepoint() {
    for (int i = Character.MIN_CODE_POINT; i <= Character.MAX_CODE_POINT; i++) {
      String expected = new StringBuilder().appendCodePoint(i).toString();
      assertEquals(expected, Character.toString(i));
    }

    try {
      throw new AssertionError(Character.toString(Character.MIN_CODE_POINT - 1));
    } catch (IllegalArgumentException expected) {
    }
    try {
      throw new AssertionError(Character.toString(Character.MAX_CODE_POINT + 1));
    } catch (IllegalArgumentException expected) {
    }
  }

  private static void assertEquals(Object expected, Object actual) {
    if (expected != actual && (expected == null || !expected.equals(actual))) {
      throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
    }
  }
}
