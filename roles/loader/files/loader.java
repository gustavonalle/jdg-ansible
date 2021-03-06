////usr/bin/env jbang "$0" "$@" ; exit $? # (1)
//FILES words.txt
//DEPS org.infinispan:infinispan-client-hotrod:9.4.21.Final 
//JAVA_OPTIONS -Xmx2g

package org.infinispan;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.marshall.UTF8StringMarshaller;

public class loader {
   private static final List<String> wordList = new ArrayList<>();
   private static final Random rand = new Random();

   static {
      try (InputStream inputStream = ClassLoader.getSystemResourceAsStream("words.txt")) {
         assert inputStream != null;
         InputStreamReader streamReader = new InputStreamReader(inputStream, UTF_8);
         BufferedReader in = new BufferedReader(streamReader);

         for (String line; (line = in.readLine()) != null; ) {
            wordList.add(line);
         }
      } catch (IOException ioe) {
         throw new RuntimeException("Could not load words file!", ioe);
      }
   }

   public static void main(String[] args) {
      String USAGE = "\nUsage: load.sh --entries num [--server host:port] [--cache name] [--write-batch num] [--phrase-size num] [--protocol version] [--security]\n";

      Runnable usage = () -> System.out.println(USAGE);

      if (args.length == 0 || args.length % 2 != 0) {
         usage.run();
         return;
      }

      Map<String, String> options = new HashMap<>();
      for (int i = 0; i < args.length; i = i + 2) {
         String option = args[i];
         if (!option.startsWith("--")) {
            usage.run();
            return;
         }
         options.put(option.substring(2), args[i + 1]);
      }
      int entries;

      String entriesValue = options.get("entries");
      String writeBatchValue = options.get("write-batch");
      String phraseSizeValue = options.get("phrase-size");
      String securityValue = options.get("security");
      String serverValue = options.get("server");
      String protocolValue = options.get("protocol");
      String cacheValue = options.get("cache");

      String host = "localhost";
      int port = 11222;
      if (serverValue != null) {
         int sep = serverValue.indexOf(':');
         if (sep != -1) {
            host = serverValue.substring(0, sep);
            port = Integer.parseInt(serverValue.substring(sep + 1));
         } else {
            host = serverValue;
         }
      }

      final int phrase_size = phraseSizeValue != null ? Integer.parseInt(phraseSizeValue) : 10;
      final int write_batch = writeBatchValue != null ? Integer.parseInt(writeBatchValue) : 10000;
      final boolean security = Boolean.parseBoolean(securityValue);
      final String cacheName = cacheValue != null ? cacheValue : "default";
      if (entriesValue == null) {
         System.out.println("option 'entries' is required");
         usage.run();
         return;
      } else {
         entries = Integer.parseInt(entriesValue);
      }

      System.out.printf("Loading %d entries with write batch size of %d and phrase size of %d\n%n", entries, write_batch, phrase_size);

      ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
      clientBuilder.addServer().host(host).port(port);
      if (security) {
         clientBuilder.security().authentication().enable().username("user").password("user");
      }
      if (protocolValue != null) {
         clientBuilder.protocolVersion(protocolValue);
      }
      clientBuilder.marshaller(new UTF8StringMarshaller());
      RemoteCacheManager rcm = new RemoteCacheManager(clientBuilder.build());
      RemoteCache<String, String> cache = rcm.getCache(cacheName);
      cache.clear();

      int nThreads = Runtime.getRuntime().availableProcessors();
      ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
      AtomicInteger counter = new AtomicInteger();
      CompletableFuture<?>[] futures = new CompletableFuture[nThreads];
      final int totalEntries = entries;
      for (int i = 0; i < nThreads; i++) {
         futures[i] = CompletableFuture.supplyAsync(() -> {
            Map<String, String> group = new HashMap<>();
            for(int j = counter.incrementAndGet(); j <= totalEntries; j = counter.incrementAndGet()) {
               group.put(String.valueOf(j), randomPhrase(phrase_size));
               if (group.size() == write_batch) {
                  cache.putAll(group);
                  System.out.printf("Loaded %s entries\r", j);
                  group = new HashMap<>();
               }
            }
            cache.putAll(group);
            return null;
         }, executorService);
      }
      System.out.println("\n");
      CompletableFuture.allOf(futures).join();
      executorService.shutdownNow();
   }

   public static String randomPhrase(int numWords) {
      return IntStream.range(0, numWords).boxed().map(i -> randomWord()).collect(Collectors.joining(" "));
   }

   public static String randomWord() {
      return wordList.get(rand.nextInt(wordList.size()));
   }

}
