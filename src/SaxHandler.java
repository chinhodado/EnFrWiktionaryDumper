import java.util.ArrayList;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Hander for the SAX parser
 */
public class SaxHandler extends DefaultHandler {
    ArrayList<String> elementStack = new ArrayList<String>();
    String currentTitle = null;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        if (qName.equals("page")) {
            currentTitle = null;
        }
        elementStack.add(qName);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        elementStack.remove(elementStack.size() - 1);
    }

    @Override
    public void characters(char ch[], int start, int length) throws SAXException {
        String value = new String(ch, start, length).trim();
        if(value.length() == 0) {
            return; // ignore white space
        }

        if("title".equals(currentElement())){
            currentTitle = value;
        }
        else if (currentElement().equals("text")) {
            // skips things like Template:foo, and no word has ":" in it anyway
            if (value.contains("==French==") && !currentTitle.contains(":")) {
                WiktionaryDumper.wordList.add(currentTitle);
            }
        }
    }

    private String currentElement() {
        return elementStack.get(elementStack.size() - 1);
    }

    private String currentElementParent() {
        if (elementStack.size() < 2) return null;
        return elementStack.get(elementStack.size() - 2);
    }
}