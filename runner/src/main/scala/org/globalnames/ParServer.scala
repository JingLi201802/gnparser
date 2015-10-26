package org.globalnames

import java.io.{BufferedOutputStream, BufferedReader, InputStreamReader, PrintStream}
import java.net.ServerSocket

import org.globalnames.parser.ScientificNameParser.{instance => scientificNameParser}

case class ParServer(port: Int = 4334) {
  def run(): Unit = {
    println(s"\nStarting Parsing Server on port $port\n")
    var line: String = ""
    val server = new ServerSocket(port)
    val sock = server.accept()
    val input = new BufferedReader(new InputStreamReader(sock.getInputStream))
    val output = new PrintStream(new BufferedOutputStream(sock.getOutputStream))
    while (true) {
      line = input.readLine.trim
      output.println(scientificNameParser.fromString(line).renderCompactJson)
      output.flush()
    }
  }
}
