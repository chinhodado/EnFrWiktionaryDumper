import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

public class WiktionaryDumper {
    // sections that we want to remove
    private static HashMap<String, Boolean> backSectionsMap;
    static
    {
        backSectionsMap = new HashMap<>();
        backSectionsMap.put("Etymology", false);
        backSectionsMap.put("Etymology 1", false);
        backSectionsMap.put("Etymology 2", false);
        backSectionsMap.put("Pronunciation", false);
        backSectionsMap.put("Conjugation", false);
        backSectionsMap.put("Anagrams", false);
    }

    private static List<String> wordList = new ArrayList<>(300000);
    private static List<String> errorList = new CopyOnWriteArrayList<>();
    private static PreparedStatement psParms;
    private static final AtomicInteger globalCounter = new AtomicInteger();
    private static final int NUM_THREAD = 8;
    private static final int MAX_RETRY = 20;
    private static int iteration = 0;

    public static void main (String argv []) throws Exception {
        logLine("Program started.");

        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");

        Statement stmt = connection.createStatement();
        String sql = "CREATE TABLE Word " +
                "( name       TEXT NOT NULL, " +
                "  definition TEXT) ";
        stmt.executeUpdate(sql);
        stmt.executeUpdate("CREATE INDEX name_idx ON Word (name)");

        psParms = connection.prepareStatement("INSERT INTO Word (name, definition) VALUES (?,?)");

        logLine("Parsing xml dump to get word list.");
        InputStream xmlInput  = new FileInputStream("C:\\Users\\trung.do\\Downloads\\enwiktionary-20150602-pages-articles.xml");
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser saxParser = factory.newSAXParser();
        SaxHandler handler = new SaxHandler();
        saxParser.parse(xmlInput, handler);
        logLine("Parsing completed. Total " + wordList.size() + " words.");

        logLine("Getting and processing Wiktionary articles using " + NUM_THREAD + " threads.");
        while (!wordList.isEmpty() && iteration < MAX_RETRY) {
            iteration++;
            doWork();
        }

        System.out.println();
        logLine("Completed all iterations.");

        // Dump the database contents to a file
        stmt.executeUpdate("backup to dict.db");
        stmt.close();
        connection.close();
        logLine("Saved to dict.db successfully. Everything done.");
    }

    private static void doWork() throws InterruptedException {
        System.out.println();
        logLine("Executing iteration " + iteration);
        int size = wordList.size();

        if (size == 0) {
            return;
        }

        int numThread = NUM_THREAD;

        if (numThread >= size) {
            numThread = 1;
        }

        // partitioning
        final int CHUNK_SIZE = size / numThread;
        final int LAST_CHUNK = size - (numThread - 1) * CHUNK_SIZE; // last chunk can be a bit bigger

        List<List<String>> parts = new ArrayList<>();
        for (int i = 0; i < size - LAST_CHUNK; i += CHUNK_SIZE) {
            parts.add(new ArrayList<>(
                wordList.subList(i, i + CHUNK_SIZE))
            );
        }

        parts.add(new ArrayList<>(
            wordList.subList(size - LAST_CHUNK, size))
        );

        List<Thread> threadList = new ArrayList<>();
        for (int i = 0; i < numThread; i++) {
            List<String> workList = parts.get(i);
            Runnable r = () -> {
                for (String word : workList) {
                    globalCounter.incrementAndGet();
                    if (globalCounter.get() % 120 == 0) System.out.println();
                    processWord(word);
                }
            };
            Thread thread = new Thread(r);
            thread.start();
            threadList.add(thread);
        }

        for(Thread t : threadList) {
            t.join();
        }

        // the errorList is now the new wordList, ready for the next iteration
        wordList = errorList;
        errorList = new ArrayList<>();
    }

    private static void processWord(String word) {
        try {
            String link = "http://en.wiktionary.org/w/index.php?title=" + URLEncoder.encode(word, "UTF-8") + "&printable=yes";
            String html = Jsoup.connect(link).ignoreContentType(true).execute().body();

            // parse and do some initial cleaning of the DOM
            Document doc = Jsoup.parse(html);
            Element content = doc.select("#mw-content-text").first();
            content.select("script").remove();               // remove <script> tags
            content.select("noscript").remove();             // remove <noscript> tags
            content.select("#toc").remove();                 // remove the table of content
            WiktionaryDumper.removeComments(content);        // remove comments
            WiktionaryDumper.removeAttributes(content);      // remove all attributes. Has to be at the end, otherwise can't grab id, etc.
            WiktionaryDumper.removeEmptyTags(content);       // remove all empty tags

            // parse for the content of the French section, if it exist
            Elements children = content.children();
            boolean frenchFound = false;
            boolean frenchEndReached = false;
            boolean isCurrentlyBackSection = false;
            Elements frenchCollection = new Elements();
            for (Element elem : children) {
                if (!frenchFound) {
                    if (elem.tagName().equals("h2") && elem.text().equals("French")) {
                        frenchFound = true;
                    }
                    else {
                        elem.remove();
                    }
                }
                else {
                    if (!elem.tagName().equals("h2")  &&                                          // French, English, etc.
                        !(elem.tagName().equals("h3") && elem.text().equals("External links")) && // remove external links section
                        !frenchEndReached) {
                        // get etylmology, pronunciation, etc. sections so that we can move them to the back
                        // of the page later, instead of having them at the beginning of the page
                        if (WiktionaryDumper.isBackSectionHeader(elem)) {
                            WiktionaryDumper.backSectionsMap.put(elem.text(), true);
                            isCurrentlyBackSection = true;
                        }
                        else if (WiktionaryDumper.isSubheaders(elem)) {
                            // something other than etymology and pronunciation, etc.
                            if (isCurrentlyBackSection) {
                                isCurrentlyBackSection = false;
                                // TODO: clear the map?
                            }
                            frenchCollection.add(elem);
                        }
                        else if (isCurrentlyBackSection) {
                            // do nothing
                        }
                        else {
                            isCurrentlyBackSection = false;
                            frenchCollection.add(elem);
                        }
                    }
                    else {
                        frenchEndReached = true;
                        break;
                    }
                }
            }

            // convert to text
            String text = frenchCollection.toString();

            // remove useless tags
            text = text.replace("<span>", "").replace("</span>", "").replace("<a>", "").replace("</a>", "")
                    .replace("<strong>", "<b>").replace("</strong>", "</b>");

            psParms.setString(1, word);
            psParms.setString(2, text);
            psParms.executeUpdate();
            System.out.print(".");
        } catch (Exception e) {
            System.out.print("." + word + ".");
            errorList.add(word);
        }
    }

    private static void logLine(String txt) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        System.out.println(dateFormat.format(date) + ": " + txt);
    }

    private static boolean isSubheaders(Element elem) {
        return elem.tagName().equals("h3") || elem.tagName().equals("h4") || elem.tagName().equals("h5");
    }

    private static boolean isBackSectionHeader(Element elem) {
        return isSubheaders(elem) && backSectionsMap.containsKey(elem.text());
    }

    private static void removeComments(Node node) {
        for (int i = 0; i < node.childNodes().size();) {
            Node child = node.childNode(i);
            if (child.nodeName().equals("#comment"))
                child.remove();
            else {
                removeComments(child);
                i++;
            }
        }
    }

    private static void removeAttributes(Element doc) {
        Elements el = doc.getAllElements();
        for (Element e : el) {
            Attributes at = e.attributes();
            for (Attribute a : at) {
                e.removeAttr(a.getKey());
            }
        }
    }

    private static void removeEmptyTags(Element doc) {
        for (Element element : doc.select("*")) {
            if (!element.hasText() && element.isBlock()) {
                element.remove();
            }
        }
    }
}
