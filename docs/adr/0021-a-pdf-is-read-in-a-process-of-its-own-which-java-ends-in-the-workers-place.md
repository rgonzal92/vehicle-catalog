# A PDF is read in a process of its own, which Java ends in the worker's place

A PDF is read by a second Java process, which the worker starts for that one PDF. The worker gives it the file and takes back the text, or the reason the file cannot be read. That process has a heap of 64 MiB, and Java ends it when its heap is full, as [it ends the worker](0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md). The worker then fails the document, with "It takes more memory to read than a PDF may.", and goes on to its next job: its own heap never held what PDFBox made of the file. Text and Markdown files are read by the worker itself, as before.

The worker reads PDFs so, and [what the build showed](#what-the-build-showed) is below. Until it did, a small PDF made to fill memory ended the worker three times, as the first trial below found.

What is counted of a PDF is [what it unpacks to](0015-documents-are-searched-in-postgresql-by-what-their-passages-mean.md), and not what PDFBox makes of it in memory. Two files within every limit are known to fill a heap as large as the worker's: one of 1,175 bytes whose font map names a range of 2,147,483,647 codes in one line, and one of 30,554 bytes whose object stream unpacks to 31,000,167 bytes and holds a list of 15.5 million empty lists. PDFBox 3.0.8 has no limit for either. There will be others of the kind, by what a page draws with or by what a font's file says of itself, that nobody has made yet. A process of its own need not know them: whatever fills its heap ends it, and nothing else.

Everything below but [what a child takes on the host](#what-a-child-takes-on-the-host) was tried on 2026-10-12 and 2026-10-13, on the machine with twelve cores that the other measurements were made on. Up to [what the build showed](#what-the-build-showed) it was tried with throwaway programs that are not kept, and the two files were made again for it, to the byte counts above.

## What such a PDF did while the worker read it itself

Each file was first read once by `DocumentText` in a process of its own with the worker's options, `-XX:MaxHeapSize=192m -XX:+ExitOnOutOfMemoryError`. Each ended it with `Terminating due to java.lang.OutOfMemoryError: Java heap space` and exit code 3, each 0.7 seconds after its process was started.

Then each was uploaded once, on 2026-10-13 from 00:17 UTC, by the demo admin on the page Documents of the local stack. The local worker was given what the host's stack file gives the host's: those options, and `restart: unless-stopped`. The local queue hides a delivered message for two minutes and sets it aside after three deliveries, as the host's does. The page, the worker's container, the two rows, and the two queues were looked at every five seconds.

| | The font map's range, 1,175 bytes | The list of lists, 30,554 bytes |
|---|---|---|
| Java ended the worker | 3 times: 1.1 seconds after the click on Upload, and two and four minutes after that | 3 times: 1.2 seconds after the click, and two and four minutes after that |
| Docker started it again | 3 times, 0.2 seconds after each end; each start logged 5.4 to 5.7 seconds | The same |
| What the page said of the document | "Waiting", then "Running" from the first look after 5 seconds until 371 seconds after the click, then "Failed" and "The job that reads it has failed. The page Jobs has what it last said." | The same, to the second |
| The document's row | Still running, with no reason. The page says "Failed" because the job has | The same |
| The job | Failed after no try that was counted, with "The queue gave up its message, and no try at the job said why." | The same |
| The message | In the dead-letter queue by 365 seconds after the click, and still there at the end | The same |

A PDF in a font of its own was uploaded after the two. It was "Ready" with "1 passage" at the first look after 5 seconds, and the worker was not started again. Its log had seven starts and six ends for want of memory.

So what [the record of documents](0015-documents-are-searched-in-postgresql-by-what-their-passages-mean.md) reasoned from the queue's settings holds. The third delivery ends the worker as the first two did. The document says "Running" for two minutes more, until the queue has given the message up, and nothing the person is shown says that the file was at fault. Each such upload leaves a message in the dead-letter queue, which the alarm on that queue goes off for.

## What a process of its own takes

A throwaway parent, started with the worker's options in `JAVA_TOOL_OPTIONS`, started a child for one PDF at a time. It wrote the file to the child's input, read the child's output to its end, and waited for the child's end. The child read the PDF with `DocumentText` as it is, so with the limits of 32 MB and the 30 seconds. The time is from before the child was started until it had ended. The memory is the most the child had resident, as Linux counts it for the process.

**Whose options the child takes.** A child takes the parent's environment unless the parent says otherwise, and `JAVA_TOOL_OPTIONS` with it:

- Started with no options of its own, it had the worker's heap of 192 MiB and the flag, and wrote `Picked up JAVA_TOOL_OPTIONS: ...` to its errors.
- Started with `-Xmx64m` of its own and the environment left alone, it had 64 MiB: what is on the command line wins.
- With `JAVA_TOOL_OPTIONS` taken out of its environment and no options of its own, it had 8 GB on this machine, a quarter of the machine's memory.

So the child is given its options on its command line, all of them, and `JAVA_TOOL_OPTIONS` is taken out of its environment.

**A PDF of 6,590 bytes in a font of its own, five starts each.** From plain jars means PDFBox's three, the one it logs through, and the backend's classes as a directory. From the backend's jar means the one file the image holds, started through Spring Boot's launcher with the class to run named (`-cp app.jar -Dloader.main=... org.springframework.boot.loader.launch.PropertiesLauncher`).

| The child's options, each with `-XX:+ExitOnOutOfMemoryError` | From | Median, ms | Range, ms | Most resident, MiB |
|---|---|---|---|---|
| `-Xmx64m` | plain jars | 293 | 283 to 314 | 102 to 103 |
| `-Xmx128m` | plain jars | 294 | 285 to 305 | 102 to 105 |
| `-Xmx64m -XX:TieredStopAtLevel=1` | plain jars | 264 | 259 to 271 | 102 |
| `-Xmx64m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | plain jars | 264 | 260 to 276 | 83 |
| The same with `-Xshare:off` | plain jars | 340 | 314 to 347 | 80 to 81 |
| The same with `-XX:ActiveProcessorCount=2` | plain jars | 283 | 261 to 290 | 81 |
| `-Xmx128m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | plain jars | 274 | 271 to 276 | 101 |
| `-Xmx64m` | the backend's jar | 643 | 635 to 654 | 133 to 162 |
| `-Xmx64m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | the backend's jar | 667 | 649 to 697 | 93 to 95 |
| `-Xmx64m`, in the backend's image, held to two processors | the backend's jar | 837 | 680 to 969 | 121 to 133 |
| `-Xmx64m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC`, in the image, held to two processors | the backend's jar | 521 | 512 to 637 | 91 |

- **Stopping Java's compiling at its first level helps a little, and the serial collector saves about 20 MiB,** and more from the backend's jar. With both, Java reports the heap as 61 MiB of the 64. Java's sharing of its own classes is on unless it is turned off, and turning it off cost 76 ms. An archive of the child's own classes, which Java can write as a process ends (`-XX:+AutoCreateSharedArchive`), was tried with five starts: they took 331 to 363 ms and the last ended with exit code 1. That was not looked into.
- **From the backend's jar a start takes about 0.4 seconds longer than from plain jars,** and the image has that jar alone. Unpacking the jar in the image would give that back, and is not done: it changes how the image is built and started for a PDF's half second.
- **For comparison,** the parent read the same PDF itself in 213 to 240 ms the first time, when PDFBox's classes were new to it, and a process that had read a PDF before read it in 42 to 196 ms.

**The two files, a PDF of 1.5 MB, and two files of noughts, once each.** The PDF of 1,552,573 bytes has 140 pages of text and a picture, 470,677 characters, and is the largest ordinary file the limits of 32 MB were tried with. The files of noughts each hold 15.5 million of them, the one as a list in an object stream and the other as a page's contents. They unpack to 31 MB and were made when those limits were set, to see what a full count costs. A process with the worker's heap of 192 MiB read each in about a second, so the worker reads them today.

| The child, with `-XX:+ExitOnOutOfMemoryError` | The font map's range | The list of lists | The PDF of 1.5 MB | The noughts in an object stream | The noughts on a page |
|---|---|---|---|---|---|
| `-Xmx64m`, plain jars | Ended with 3 after 0.7 s, 137 MiB | Ended with 3 after 0.4 s, 127 MiB | Read in 0.8 s, 158 MiB | | |
| `-Xmx128m`, plain jars | Ended with 3 after 0.8 s, 207 MiB | Ended with 3 after 0.5 s, 192 MiB | Read in 0.7 s, 194 MiB | | |
| `-Xmx64m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC`, plain jars | Ended with 3 after 1.7 s, 116 MiB | Ended with 3 after 0.5 s, 112 MiB | Read in 0.9 s, 87 MiB | Ended with 3 after 1.9 s, 112 MiB | Ended with 3 after 0.9 s, 116 MiB |
| `-Xmx128m` and the same two, plain jars | Ended with 3 after 4.0 s, 181 MiB | Ended with 3 after 1.0 s, 176 MiB | Read in 0.9 s, 107 MiB | Ended with 3 after 1.7 s, 164 MiB | Ended with 3 after 1.4 s, 160 MiB |
| `-Xmx64m` and the same two, in the image, held to two processors, the backend's jar | Ended with 3 after 0.8 s, 123 MiB | Ended with 3 after 0.7 s, 120 MiB | Read in 1.0 s, 95 MiB | | |

- **Each of the two files ended the child and left the parent running,** in every row. After each row the parent read a PDF itself, and its heap limit was the worker's 192 MiB throughout.
- **Beside its heap, Java took 50 to 60 MiB:** a child with 61 MiB of heap had 112 to 123 MiB resident when its heap was full.
- **Java writes its last line to the child's output,** where the text travels: `Terminating due to java.lang.OutOfMemoryError: Java heap space` came back as 63 bytes of text. So the worker goes by how the child ended, and takes its output for the text only when it ended with 0.
- **Started from the backend's jar, PDFBox's warnings went to the child's output as well.** The PDF of 1.5 MB came back with `WARN org.apache.pdfbox.pdmodel.font.PDType1Font -- Using fallback font LiberationSans for Helvetica` before its first line, 488,457 bytes where the text is 470,677. From plain jars they went to the child's errors. So the child sends what it logs to its errors.
- **The files of noughts ended a child of 64 MiB, and one of 128.** The worker reads them today. They are made to be as dense as a file can be, and no ordinary PDF is known that is near them.

**Against the host's room.** With the two heaps and the cache all at their limits the host has [about 345 MiB to spare](../host-memory-measurements.md). A child at its worst, with its heap full, had 123 MiB resident in the image, so about 220 would be left with every limit reached and a PDF being read. A child of 128 MiB had 181 at its worst from plain jars, which would leave about 160. On the host a child at its worst has since had 115 MiB resident, which leaves [about 230](#what-a-child-takes-on-the-host). The worker is not near its own limit while it waits for a child: it holds the file, of 2 MB at most, and the text.

All of this is of a machine with twelve cores and an x86-64 processor. The host has two cores of another kind, and the API's start, which logs 7.7 seconds on this machine, logs about 20 there. By that a child would take well over a second on the host for a small PDF. That was a guess from one ratio, made before the host ran an image that holds the child. Both the time and the memory have since been [measured on the host](#what-a-child-takes-on-the-host), as [the record of the worker](0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md) asks for a third process.

## What a worker without the flag does with the two files

Each file was read in a process with the worker's heap limit and without `-XX:+ExitOnOutOfMemoryError`, on a thread of its own as `DocumentText` reads it. Beside it a second thread asked for 64 KB every 5 ms, as the worker's other threads ask for a little all the time. Then a PDF in a font of its own was read in the same process. This was run twice, the second time to see where the memory was asked for.

| | The font map's range | The list of lists |
|---|---|---|
| The document would have failed | After 0.6 s both times, as one that could not be read as a PDF | After 0.7 and 1.1 s, the same |
| The error fell on | The reading's thread, where a `HashMap` asked for one large table (`HashMap.resize`) | The reading's thread, where PDFBox made one more small list (`COSArray.<init>`) |
| The thread beside it was refused memory | Never | Never the first time, twice the second |
| The heap afterwards | 174 MiB in use, 10 after a collection | 36 MiB in use, 3 after a collection |
| The next PDF | Read in 42 and 50 ms | Read in 178 and 196 ms |

So a reading that is refused memory does end, its thread ends with it, what it held is given back, and the next PDF is read. But the error does not fall on the reading's thread alone. The font map's range fails by one request for a large table, which leaves the rest of the heap free for everyone else. The list of lists fills the heap to the last small object, and then whichever thread asks next is refused: the thread beside the reading was, twice in one of two runs. It caught the error and went on, because the trial's program had it do so.

What a thread of the worker's own does when it is refused was not tried. [The API's trial](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md) has what one did: a thread the API cannot do without ended, and the API answered nothing for as long as it was left. The worker has such threads too: the ones that send the jobs' messages and wait on the queue, the ones that keep its connections to the database and to AWS, and the server that says whether it is ready.

## What the build showed

Tried on 2026-10-13, on the same machine, with the worker starting the child itself. The tests' `Pdfs` makes the two files, at 1,194 and 30,545 bytes on this machine. The pipeline's run made the first at 1,192.

**From class directories, as the tests run.** There is no jar there, so the child is started by its class's name, with the worker's class path and the same options. Each of the two files failed with "It takes more memory to read than a PDF may.", after 1.2 and 0.4 seconds, and the PDF after each was read in under 0.4 seconds, a tenth of which is the test's own wait. The tests that were there pass as they were. A file that is refused for what it unpacks to now takes about 0.2 seconds to fail, where it took 5 ms, and the PDF after it 0.37 seconds, where it took 0.1: each is a start of Java.

**From the image's jar, on the local stack,** with the host's options and restart policy given to the worker. The demo admin uploaded the two files and then a PDF of 6,590 bytes in a font of its own, on the page Documents.

| | The font map's range | The list of lists | The PDF uploaded next |
|---|---|---|---|
| What the page said | "Waiting", then "Failed" and "It takes more memory to read than a PDF may." at its look 3.1 seconds after the click on Upload | The same | "Waiting", then "Ready" and "1 passage" at its look after 3.1 seconds |
| The job | Done in 762 ms, after one try | Done in 708 ms, after one try | Done in 503 ms, after one try |

- The worker's container had the start time it had before, and no restart counted. Its log had one start and no end for want of memory.
- The queue and the dead-letter queue held no message, before and after.
- The trial was run once more with the build as it was merged, after the review's fix to how a child that writes too much is ended. It showed the same: both files "Failed" with that reason at the page's look after 3.1 seconds, the PDF uploaded next "Ready", the jobs done in 730, 701, and 504 ms after one try, the same start time before and after, no restart, and both queues empty. The one passage of the PDF uploaded next was read from the database that time: `It comes to Zürich first.`, which is the PDF's line.
- While each PDF was read the container held a second `java` process, started by the worker as the image's user: `/usr/lib/jvm/java-25-amazon-corretto/bin/java -Xmx64m -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dloader.main=dev.rgonz.catalog.document.PdfReading -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher`.

**What the child has of the environment.** The worker gives it none: not `JAVA_TOOL_OPTIONS`, and not the passwords and keys the worker holds, since the child reads a file anyone may have made. In the image the child was seen to hold one name all the same, `LD_LIBRARY_PATH`, in 105 bytes. Java's launcher there sets it for itself, to Java's own two directories, and starts itself again: a `java` started with an empty environment in a container of the image alone held the same. The child runs as the worker's user. So this keeps the worker's secrets out of what the child is handed, and is not meant as a wall between the two processes: what one process of a user can read of another's was not tried.

**Started as the worker starts it from that jar, with an empty environment, on this machine and outside the image,** one start each. The PDF of 6,590 bytes was read in 501 ms with 90 MiB resident at most. The two files ended the child with 3 after 760 and 659 ms, with 121 and 119 MiB. The figures above, of the trials before the build, were 521 ms and 91 MiB in the image, and 123 and 120 MiB at its worst.

**What the record had left open.**

- A child that cannot be started is no fault of the file: the reading fails as the job's own failure does, and not with a reason for the document. A test holds that. That the job is then tried again is how every other failure of a job goes, and was not run for this one.
- A child that a signal ended fails its file as one that could not be read as a PDF, and the worker logs how it ended: 137 in the test, for a child that was killed.
- A reading that was given a tenth of a second for a PDF that takes far longer failed as one that took too long, and its child was gone within a second.
- The file, the text, and the child's errors each travel on a thread of their own in the worker, so that neither side waits for the other to take what it writes. The worker takes of the child's output the most text a document may have, in bytes, which is 1,440,000. A child that writes more is ended, and its file fails as one that could not be read as a PDF, also when the child had written the last of it and ended with 0 by itself. A test holds both, with a process that writes one byte more and one that writes two million. Such a child is ended through its handle, which leaves the worker's ends of its output and errors open: ended as a process, its errors were closed under the thread that read them, and the reading failed as the job's own failure does. Of its errors the worker passes the first 8 KiB on to its own log, as one warning, and reads the rest without keeping it. No test that is kept reaches that limit. A throwaway one did, with 100,000 bytes of errors: the text came back, with one warning that ended "and 91,808 bytes more that are not kept".
- A worker that is told to stop while a child reads waits for that job as for any other, within the ten seconds Docker gives it. A child cannot outlast the worker's container, whose processes end with its first. That was reasoned and not tried. What is so outside a container: a job's thread that is interrupted while a child reads gets "It was not read to its end.", as before, and nothing ends its child, which reads on until it is done. A throwaway test saw that child alive half a second later.

## What a child takes on the host

Measured on 2026-10-13 from 07:15 to 07:20 UTC, on the host, once it ran the image that holds the child. The release that brought it had started the API at 07:10:23 and the worker at 07:10:49. A command on the host read `/proc` twenty times a second, and fifty times for the last two rows, and wrote down what every `java` process had resident and what the host had available. [The measurements](../host-memory-measurements.md) have how.

| | The child was there for | Most it had resident, MiB | Least the host had available meanwhile, MiB |
|---|---|---|---|
| A PDF of 3 pages of text, 2,394 bytes, uploaded by the demo admin | 1.36 s | 76 | 474 |
| A PDF of 60 pages of text, 37,675 bytes and 248,510 characters, uploaded so | 2.07 s | 78 | 424 |
| The font map's range, 1,194 bytes, given to a child started by hand | 1.73 s | 115 | 378 |
| The list of lists, 30,545 bytes, given so | 1.60 s | 113 | 376 |

- **With every limit reached, about 230 MiB are left.** The host has about 345 MiB to spare with the two heaps and the cache at their limits, and a child at its worst had 115 resident. The host had 477 MiB available before the two at their worst, and never less than 376.
- **The two ordinary PDFs** were ready on the page at its looks 7.1 and 6.6 seconds after the click on Upload, with 7 and 177 passages. Their jobs were done in 4.3 and 5.6 seconds after one try, the embedding model asked included. What such a job took on the host while the worker read a PDF itself was not measured. Both documents were deleted afterwards.
- **Nothing of Java's own was in the text.** Java writes warnings of its own to a process's output, where the text travels, and the host's processor is of another kind than the one everything above was tried on. So the passages of both were read from the host's database. They name every one of the 120 numbered lines of the first and the 3,000 of the second, the first passage begins with the PDF's first line, and none holds a bracket or a word of a warning of Java's.
- **The child was started as on the local stack:** by the worker's process, as user 10001, with the command line above, and with `LD_LIBRARY_PATH` alone in its environment, in 105 bytes.
- **What PDFBox said reached the worker's log,** as one warning for each PDF: `Using fallback font LiberationSans for Helvetica`, since the two PDFs are written in a font they do not hold. On the local stack no child had written to its errors.
- **The two files made to fill memory were not uploaded to the deployed app.** A child was started by hand in the worker's container for each, as the container's user, with that command line and an empty environment, and the file on its input. Each ended with exit code 3, 1.8 and 1.7 seconds after `docker exec` was called, and wrote Java's last line to its output and nothing to its errors.
- **The worker and the API** had the start times they had, and no restart counted, before and after. The worker's log had one start, no end for want of memory, and no line at the level of a warning or an error but the two above. The queue and the dead-letter queue held no message before and after each, and the three alarms were `OK` before and after.
- **The guess above was that a child would take well over a second on the host for a small PDF, and it took 1.4.** The host had all its processor credits, 576. Each figure is one reading.

## Considered Options

- **Every font map and object stream is looked at before PDFBox reads it.** Code of our own would read what is unpacked, and refuse a font map whose ranges name more codes than a number and an object stream that holds more values than a number.
  - *What it closes:* the two files, and nothing the look does not know of.
  - *What it costs:* a second reader of PDF's objects and of font maps that has to agree with PDFBox's on every file. PDFBox's own are 1,511 lines for objects (`BaseParser`), 234 for object streams, and 856 for font maps (`CMapParser`). It would count lists within lists, tell a bracket in a string or a comment from one that opens a list, and find the ranges of a font map among PostScript. Where the two disagree, the look lets through what PDFBox then fills the heap with, or refuses a file that reads well. It takes no memory.
  - *The two numbers* were not chosen, because each needs trials of its own that this way does not earn. What is known of them: 15.5 million noughts in one list are read in a heap of 192 MiB and 15.5 million empty lists are not, so what counts is the values that PDFBox makes an object of, and not the bytes. A font map of 1,905,400 single codes was read in such a heap and left it at 36 MB, and a font has no more than 65,536 codes of two bytes.
  - *What stays open:* a page's operands, a font's file, and every way not thought of. The look leans on how PDFBox is built inside, as the count of what is unpacked already does.
- **Java does not end the worker when its heap is full.** The worker would be started without `-XX:+ExitOnOutOfMemoryError`. A reading that fills the heap then fails on its own thread, and its document fails as one that could not be read as a PDF, as the trial above has it.
  - *What it closes:* nothing by itself. In the trials both files failed their document within about a second and the next PDF was read, which is all that is asked for. It held because the reading's thread asks for nearly all the memory, and so is nearly always the one refused.
  - *What it costs:* one line of the host's stack file, the infrastructure test that holds the worker to the flag, a comment in the code that runs jobs, and [two](0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md) [records](0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md). No memory. And it gives up, for every job, what the flag was set for: a worker whose heap is full, from an export as well as from a PDF, would go on with whatever the error left of it.
  - *What stays open:* which thread is refused is chance, and the thread beside the reading was refused in one run of two. A worker that has lost a thread it cannot do without does no jobs and is not started again by anything: its process has not ended. The alarm on the oldest waiting message would show it ten minutes later, and tells no one.
  - *The flag kept, and the error met some other way:* none was found. Java ends the process at the first such error wherever it is thrown, before any code of ours could catch it. Without the flag, code could end the worker for an error on any thread but the reading's, but only for errors it gets to see: one that a library catches and passes over is not seen.
- **Leaving it as it is.** The harm of one such upload is what the first trial found: the worker ends three times in four minutes, the document says "Running" for six and then fails with a reason that does not name the file, and a message stays in the dead-letter queue and sets off its alarm.
  - *What speaks for it:* nothing is lost. The API is not touched, [no other job loses a delivery](0012-the-worker-runs-beside-the-api-on-the-t4g-small-with-a-limit-on-its-heap.md), the jobs that arrive while the worker is away wait for its start, which logged under 6 seconds here, where the API's logs 7.7 and on the host about 20, and the demo is reset every day. It costs no code and no memory.
  - *What speaks against it:* the demo's admin account is a published login, and a file of a kilobyte does it. There may be twenty documents at a time, and a document can be deleted and another uploaded, so the worker can be kept ending for as long as someone cares to. Each end is a start more on the dashboard and each upload a message for the alarm, which are the two places that would show a worker ending for a reason nobody intended. And an honest PDF that needs more than the worker has, if there is one, is treated the same: six minutes of "Running" and a reason that says to look elsewhere.
- **A heap of 128 MiB for the child.** It had 181 MiB resident at its worst where 64 has 116, and took 4.0 seconds to end for the font map's range where 64 took 1.7. The PDF of 1.5 MB was read in 64, and the files of noughts ended both. Nothing that was tried is read in 128 and not in 64.
- **A child that is kept running and given one PDF after another.** It would save the start, about half a second a PDF. It would hold its memory all day for a few PDFs, and what one file left in it would be there for the next.

## Consequences

- What is built, and no more:
  - The backend's jar has a second class to start, which reads a PDF from its input and writes the text to its output. It reads as `DocumentText` reads today, with the two limits of 32 MB, the most text a document may have, and what is refused before it is unpacked. PDFBox is no longer run in the worker.
  - The worker starts it with the Java it runs on itself and `-Xmx64m -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -XX:+UseSerialGC`, from its own jar through Spring Boot's launcher, and gives it none of its own environment: `JAVA_TOOL_OPTIONS` is not in it, and neither is anything else the worker holds. Where the worker runs from class directories, as in the tests, the child's class is started by its name. The image is not changed.
  - A child that ends with 0 gave the text. One that ends with a code of its own gave the reason, which is one of those a document fails with today. One that Java ended for a full heap, with 3, fails the document with "It takes more memory to read than a PDF may." Any other end fails it as one that could not be read as a PDF. A child that cannot be started is no end of a child: it is the job's failure, and not the file's.
  - A child that has not ended when the 30 seconds are over is ended by the worker, and the document fails as one that took too long, as today. No reading is left behind any more, so the next PDF no longer waits for one. One PDF is read at a time, as before.
  - The child logs to its errors, and of what PDFBox logs, the warnings and errors. The worker passes the start of them on to its own log.
- A document that fails so fails once, and for good: the job is done, as for [every file that cannot be read](0015-documents-are-searched-in-postgresql-by-what-their-passages-mean.md). So one such upload costs one child, for a second or two, where it cost three starts of the worker. Nothing reaches the dead-letter queue.
- Every PDF takes about half a second longer to read on the machine with twelve cores, and more on the host, where a child was there for 1.4 seconds for a PDF of three pages and 2.1 for one of sixty. As built, the job of a PDF of 6,590 bytes was done in 503 ms on the local stack, where the job of such a PDF was done in 94 ms when the worker read it itself. The page shows a document's status when it next looks, every 3 seconds.
- While a PDF is read the host holds a third Java process. Measured on the host, it had 76 and 78 MiB resident for two ordinary PDFs and 115 at its worst, where the image on the machine with twelve cores had 91 and about 123. With every limit reached that leaves about 230 MiB of the 345, and the host had 376 MiB available at the least while a child at its worst ran. So it fits.
- A PDF that needs more than 64 MiB to be read is refused where the worker's 192 read it. The two files of noughts are, and nothing else that was tried is. The limit is one number on the child's command line.
- Still open: such an upload takes a child's memory and a second or two of a processor each time, 1.7 and 1.6 seconds on the host for the two files, and anyone signed in as the demo's admin can make one. What a child costs the host's two cores while the API answers was not measured, and neither was a child on a host whose processor credits are spent.
- A PDF made to fill memory by a way nobody has thought of ends the child as these two do. Nothing has to be known about it first, and nothing here leans on how PDFBox is built inside. The count of what is unpacked stays, for the files it refuses in milliseconds.
- [The record of documents](0015-documents-are-searched-in-postgresql-by-what-their-passages-mean.md) says what holds for the two files, and the tests' `Pdfs` makes both. [The measurements](../host-memory-measurements.md) have the host with a child on it, measured on 2026-10-13 once the host ran the image that holds the child.
