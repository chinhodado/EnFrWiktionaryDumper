A tool to dump the EN-FR Wiktionary to a SQLite database.

First, you need to download the XML dump of the EN Wiktionary. This is needed to get the list of words in a language that have English definition, since Wiktionary provides no API for that purpose (as of this writing), nor does it have a category for that.

To download that file, go here: https://dumps.wikimedia.org/backup-index.html. Choose enwiktionary, and choose the enwiktionary-xxxxxxxx-pages-articles.xml.bz2 file (~500 MB). Then extract it, and you'll get an XML file (~4 GB).

Now go to WiktionaryDumper.java and change the path to the file. Also, change the number of threads that you want to run in parallel for doing the work (default is 8). Don't set it too high, since if you send requests too fast your IP may get banned.

After the program completes, it will create a dict.db file in the project folder.
