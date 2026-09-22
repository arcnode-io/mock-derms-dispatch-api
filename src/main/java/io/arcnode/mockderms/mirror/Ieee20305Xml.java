package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePoint;
import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePointElement;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;

/**
 * Parses a real IEEE 2030.5 {@code MirrorUsagePoint} document via JAXB classes generated directly
 * from {@code src/main/resources/xsd/sep.xsd}. No schema validation on this side — incoming
 * validation is lower priority than outgoing (a malformed document from der-control-api is a bug on
 * the sending side, not something the utility mock needs to police); JAXB unmarshalling itself
 * still fails loudly on structurally invalid XML.
 */
public final class Ieee20305Xml {

  private Ieee20305Xml() {}

  public static MirrorUsagePoint unmarshal(String xml) {
    try {
      JAXBContext context = JAXBContext.newInstance(MirrorUsagePointElement.class);
      Unmarshaller unmarshaller = context.createUnmarshaller();
      return (MirrorUsagePointElement)
          unmarshaller.unmarshal(new StreamSource(new StringReader(xml)));
    } catch (JAXBException e) {
      throw new IllegalArgumentException("failed to parse MirrorUsagePoint", e);
    }
  }
}
