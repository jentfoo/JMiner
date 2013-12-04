package org.litecoinpool.miner;

import static org.junit.Assert.*;

import java.security.GeneralSecurityException;

import org.junit.Test;

public class WorkTest {
  @Test
  public void hashTest() throws GeneralSecurityException {
    byte[] header = Work.hexStringToByteArray("01000000f615f7ce3b4fc6b8f61e8f89aedb1d0852507650533a9e3b10b9bbcc30639f279fcaa86746e1ef52d3edb3c4ad8259920d509bd073605c9bf1d59983752a6b06b817bb4ea78e011d012d59d4");
    byte[] hash = new Hasher().hash(header);
    
    assertEquals("d9eb8663ffec241c2fb118adb7de97a82c803b6ff46d57667935c81001000000", 
                 Work.byteArrayToHexString(hash));
  }
}
