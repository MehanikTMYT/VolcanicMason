#!/usr/bin/env python3
"""Issue commands only to this worktree's isolated QA server."""
import socket, struct, sys
def command(text):
    with socket.create_connection(('127.0.0.1',25942),timeout=10) as sock:
        def read(count):
            data=b''
            while len(data)<count:
                part=sock.recv(count-len(data))
                if not part: raise EOFError('RCON closed')
                data+=part
            return data
        def send(kind,body):
            packet=struct.pack('<ii',7,kind)+body.encode()+b'\0\0'
            sock.sendall(struct.pack('<i',len(packet))+packet)
            data=read(struct.unpack('<i',read(4))[0])
            if struct.unpack('<i',data[:4])[0] == -1: raise RuntimeError('RCON authentication failed')
            return data[8:-2].decode()
        send(3,'vmqa-local-only')
        return send(2,text)
if __name__ == '__main__':
    for text in sys.argv[1:]: print(command(text))
