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
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

/**
 * Dump wiktionary to raw_en-fr_dict.db with minimal processing
 */
public class WiktionaryDumper {
    private static List<String> wordList = new ArrayList<>(300000);
    private static List<String> errorList = new CopyOnWriteArrayList<>();
    private static PreparedStatement psParms;
    private static final AtomicInteger doneCounter = new AtomicInteger();
    private static final int NUM_THREAD = 8;
    private static int iteration = 0;
    private static int totalWords;

    public static void main (String argv []) throws Exception {
        logLine("Program started.");

        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");

        Statement stmt = connection.createStatement();
        String sql = "CREATE TABLE Word " +
                "( name       TEXT NOT NULL, " +
                "  definition TEXT) ";
        stmt.executeUpdate(sql);

        psParms = connection.prepareStatement("INSERT INTO Word (name, definition) VALUES (?,?)");

        logLine("Parsing xml dump to get word list.");
        InputStream xmlInput  = new FileInputStream("C:\\Users\\tdo\\Downloads\\enwiktionary-20170120-pages-articles.xml");
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser saxParser = factory.newSAXParser();
        SaxHandler handler = new SaxHandler();
        saxParser.parse(xmlInput, handler);
        logLine("Parsing completed. Total " + wordList.size() + " words.");
        totalWords = wordList.size();

        logLine("Getting and processing Wiktionary articles using " + NUM_THREAD + " threads.");
        Scanner in = new Scanner(System.in);
        while (!wordList.isEmpty()) {
            iteration++;
            doWork();

            if (!wordList.isEmpty()) {
                System.out.println("Do you want to retry the " + wordList.size() + " words with error? (y/n)");
                String s = in.next();
                if (s.equals("n")) {
                    break;
                }
            }
        }

        System.out.println();
        logLine("Completed all iterations.");

        // Dump the database contents to a file
        stmt.executeUpdate("backup to raw_en-fr_dict.db");
        stmt.close();
        connection.close();
        logLine("Saved to raw_en-fr_dict.db successfully. Everything done.");
    }

    private static void doWork() throws InterruptedException {
        System.out.println();
        int size = wordList.size();
        logLine("Executing iteration " + iteration + ", words left: " + size);

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
                    processWord(word);
                }
            };
            Thread thread = new Thread(r);
            thread.start();
            threadList.add(thread);
        }

        Thread logThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                System.out.print("\r");
                int done = doneCounter.get();
                int error = errorList.size();
                double percentage = (double)done / totalWords * 100;
                System.out.print("Completed: " + done + "/" + totalWords + "(" + percentage + "%), error: " + error + "                  ");

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        logThread.start();

        for(Thread t : threadList) {
            t.join();
        }

        logThread.interrupt();
        logThread.join();

        if (!errorList.isEmpty()) {
            System.out.println("\nError remaining: ");
            for (String s : errorList) {
                System.out.print(s + " | ");
            }
            System.out.println();
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
            Elements frenchCollection = new Elements();
            for (Element elem : children) {
                if (!frenchFound) {
                    if (elem.tagName().equals("h2") && elem.text().equals("French")) {
                        frenchFound = true;
                    }
                }
                else {
                    if (!elem.tagName().equals("h2")) {  // French, English, etc.
                        frenchCollection.add(elem);
                    }
                    else {
                        break;
                    }
                }
            }

            // convert to text
            String text = frenchCollection.toString();

            // remove useless tags
            text = text.replace("<span>", "").replace("</span>", "").replace("<a>", "").replace("</a>", "")
                    .replace("<strong>", "<b>").replace("</strong>", "</b>").replace("<img>", "");

            psParms.setString(1, word);
            psParms.setString(2, text);
            psParms.executeUpdate();
            doneCounter.incrementAndGet();
        } catch (Exception e) {
            errorList.add(word);
        }
    }

    private static void logLine(String txt) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        System.out.println(dateFormat.format(date) + ": " + txt);
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
            e.clearAttributes();
        }
    }

    private static void removeEmptyTags(Element doc) {
        for (Element element : doc.select("*")) {
            if (!element.hasText() && element.isBlock()) {
                element.remove();
            }
        }
    }

    static List<String> getWordList() {
        return wordList;
    }
}