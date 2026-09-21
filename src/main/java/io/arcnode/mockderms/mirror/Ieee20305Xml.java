package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePoint;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
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
      JAXBContext context = JAXBContext.newInstance(MirrorUsagePoint.class);
      // Reason: MirrorUsagePoint has no @XmlRootElement of its own (root association comes from
      // ObjectFactory.createMirrorUsagePoint on the sending side) — the declaredType overload
      // tells the unmarshaller what to bind the root element to instead of guessing.
      JAXBElement<MirrorUsagePoint> element =
          context
              .createUnmarshaller()
              .unmarshal(new StreamSource(new StringReader(xml)), MirrorUsagePoint.class);
      return element.getValue();
    } catch (JAXBException e) {
      throw new IllegalArgumentException("failed to parse MirrorUsagePoint", e);
    }
  }
}
