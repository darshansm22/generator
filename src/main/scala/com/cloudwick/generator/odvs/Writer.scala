package com.cloudwick.generator.odvs

import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import com.cloudwick.generator.utils.{AvroFileHandler, FileHandler, Utils}
import scala.collection.mutable.ArrayBuffer
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.Schema
import java.io.File

/**
 * Writes events to file
 * @author ashrith 
 */
class Writer(eventsStartRange: Int,
                 eventsEndRange: Int,
                 counter: AtomicLong,
                 sizeCounter: AtomicLong,
                 config: OptionsConfig) extends Runnable {
  lazy val logger = LoggerFactory.getLogger(getClass)
  lazy val utils = new Utils
  lazy val fileUtils = new utils.FileUtils
//  lazy val ipGenerator = new IPGenerator(config.ipSessionCount, config.ipSessionLength)
//  lazy val logEventGen = new LogGenerator(ipGenerator)
  lazy val schemaLine ="""
                    |{
                    | "type":"record",
                    | "name":"ODVS",
                    | "fields":[
                    |   {"name":"CId","type":"string"},
                    |   {"name":"CName","type":"string"},
                    |   {"name":"UserActive","type":"int"},
                    |   {"name":"TimeStamp","type":"string"},
                    |   {"name":"PauseTime","type":"string"},
                    |   {"name":"MovieRating","type":"int"},
                    |   {"name":"MovieID","type":"string"},
                    |   {"name":"MovieName","type":"string"},
                    |   {"name":"MovieReleaseDate","type":"string"},
                    |   {"name":"MovieLength","type":"int"},
                    |   {"name":"MovieGenre","type":"string"}
                    |  ]
                    |}
                  """.stripMargin

  lazy val sleepTime = if(config.eventsPerSec == 0) 0 else 1000/config.eventsPerSec

  def threadName = Thread.currentThread().getName

  def formatEventToString(logEvent: LogEvent) = {
    s"${logEvent.ip} - - [${logEvent.timestamp}]" + " \"GET " + logEvent.request + " HTTP/1.1\" +" +
    s" ${logEvent.responseCode} ${logEvent.responseSize} " + "\"-\" \"" + logEvent.userAgent + "\"\n"
  }

  def avroEvent(event: LogEvent) = {
    val schema = new Schema.Parser().parse(schemaDesc)
    val datum: GenericRecord = new GenericData.Record(schema)
    datum.put("OriginatingIp", event.ip)
    datum.put("ClientIdentity", "-")
    datum.put("UserID", "-")
    datum.put("TimeStamp", event.timestamp)
    datum.put("RequestType", "GET")
    datum.put("RequestPage", event.request)
    datum.put("HTTPProtocolVersion", "HTTP/1.1")
    datum.put("ResponseCode", event.responseCode)
    datum.put("ResponseSize", event.responseSize)
    datum.put("Referrer", "-")
    datum.put("UserAgent", event.userAgent)
    datum
  }

  def run() = {
    val totalEvents = eventsEndRange - eventsStartRange + 1
    var batchCount: Int = 0
    val outputFile = new File(config.filePath, s"mock_apache_${threadName}.data").toString
    lazy val fileHandlerText = new FileHandler(outputFile, config.fileRollSize)
    lazy val fileHandlerAvro = new AvroFileHandler(outputFile, schemaDesc, config.fileRollSize)
    var eventsAvro = new ArrayBuffer[GenericRecord](config.flushBatch)
    var eventsText  = new ArrayBuffer[String](config.flushBatch)
    val ipGenerator = new IPGenerator(config.ipSessionCount, config.ipSessionLength)
    val logEventGenerator = new LogGenerator(ipGenerator)
    var logEvent: LogEvent = null

    try {
      if (config.fileFormat == "avro") {
        fileHandlerAvro.openFile()
      } else {
        fileHandlerText.openFile()
      }
      (eventsStartRange to eventsEndRange).foreach { eventCount =>
        batchCount += 1
        logEvent = logEventGenerator.eventGenerate
        sizeCounter.getAndAdd(logEvent.toString.getBytes.length)
        if (config.fileFormat == "avro") {
          eventsAvro += avroEvent(logEvent)
        } else {
          eventsText += formatEventToString(logEvent)
        }
        counter.getAndIncrement
        if (batchCount == config.flushBatch || batchCount == totalEvents) {
          if (config.fileFormat == "avro") {
            fileHandlerAvro.publishBuffered(eventsAvro)
            eventsAvro.clear()
          } else {
            fileHandlerText.publishBuffered(eventsText)
            eventsText.clear()
          }
          batchCount = 0
        }
      }
      logger.debug(s"Events generated by $threadName is: $totalEvents from ($eventsStartRange) to ($eventsEndRange)")
    } catch {
      case e: Exception => {
        logger.error("Error:: {}", e)
      }
    }
    finally {
      if (config.fileFormat == "avro") {
        fileHandlerAvro.close()
      } else {
        fileHandlerText.close()
      }
    }
  }
}