package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.mirror.ieee20305.DERControl;
import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePoint;
import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePointElement;
import io.arcnode.mockderms.mirror.ieee20305.NotificationElement;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.springframework.core.io.ClassPathResource;
import org.xml.sax.SAXException;

/**
 * Reads and writes real IEEE 2030.5 XML via JAXB classes generated directly from {@code sep.xsd}.
 * Incoming {@code MirrorUsagePoint} documents are unmarshalled without schema validation (a
 * malformed document from der-control-api is a bug on the sending side, and JAXB still fails loudly
 * on structurally invalid XML); outgoing documents are validated in tests, which is where a
 * well-typed but semantically wrong value gets caught.
 */
public final class Ieee20305Xml {

  private static final String SCHEMA_RESOURCE = "xsd/sep.xsd";

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

  /** Marshals a {@code NotificationElement} as the XML document's root element. */
  public static String marshal(NotificationElement notification) {
    try {
      // Reason: DERControl travels in Notification's Resource slot, whose declared type is the
      // Resource base. JAXB only emits the xsi:type that makes that legal if the runtime class is
      // in the context, so it is named explicitly.
      JAXBContext context = JAXBContext.newInstance(NotificationElement.class, DERControl.class);
      Marshaller marshaller = context.createMarshaller();
      StringWriter writer = new StringWriter();
      marshaller.marshal(notification, writer);
      return writer.toString();
    } catch (JAXBException e) {
      throw new IllegalStateException("failed to marshal Notification", e);
    }
  }

  /**
   * @throws SAXException if {@code xml} does not validate against the real IEEE 2030.5 schema
   */
  public static void validate(String xml) throws SAXException {
    Validator validator = loadSchema().newValidator();
    try {
      validator.validate(new StreamSource(new StringReader(xml)));
    } catch (IOException e) {
      // Reason: a StringReader-backed Source cannot fail with a real I/O error; the checked
      // signature is Validator's.
      throw new UncheckedIOException(e);
    }
  }

  private static Schema loadSchema() {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      return factory.newSchema(
          new StreamSource(new ClassPathResource(SCHEMA_RESOURCE).getInputStream()));
    } catch (SAXException e) {
      throw new IllegalStateException("failed to compile " + SCHEMA_RESOURCE, e);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + SCHEMA_RESOURCE, e);
    }
  }
}
