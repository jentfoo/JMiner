package org.litecoinpool.miner;

public class Base64 {
  private final static char[] BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
  private static int[] toInt = new int[128];
  
  static {
    for (int i = 0; i < BASE64_ALPHABET.length; i++) {
      toInt[BASE64_ALPHABET[i]] = i;
    }
  }
  
  public static String stringToBase64(String str) {
    byte[] buf = str.getBytes();
    int size = buf.length;
    char[] ar = new char[((size + 2) / 3) * 4];
    int a = 0;
    int i = 0;
    while (i < size) {
      byte b0 = buf[i++];
      byte b1 = (i < size) ? buf[i++] : 0;
      byte b2 = (i < size) ? buf[i++] : 0;
      ar[a++] = BASE64_ALPHABET[(b0 >> 2) & 0x3f];
      ar[a++] = BASE64_ALPHABET[((b0 << 4) | ((b1 & 0xFF) >> 4)) & 0x3f];
      ar[a++] = BASE64_ALPHABET[((b1 << 2) | ((b2 & 0xFF) >> 6)) & 0x3f];
      ar[a++] = BASE64_ALPHABET[b2 & 0x3f];
    }
    switch (size % 3) {
      case 1:
        ar[--a] = '=';
        // notice no break
      case 2:
        ar[--a] = '=';
    }
    
    return new String(ar);
  }
}
