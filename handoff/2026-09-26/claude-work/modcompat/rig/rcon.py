import socket, struct, sys
pw='hsmc-local'
def pkt(i,t,body):
    b=body.encode()+b'\x00\x00'; return struct.pack('<iii',len(b)+8,i,t)+b
def recv(s):
    h=b''
    while len(h)<4: h+=s.recv(4-len(h))
    n=struct.unpack('<i',h)[0]; d=b''
    while len(d)<n: d+=s.recv(n-len(d))
    return struct.unpack('<ii',d[:8])[0], d[8:-2].decode(errors='replace')
s=socket.create_connection(('127.0.0.1',25652),timeout=30)
s.sendall(pkt(1,3,pw)); rid,_=recv(s)
if rid==-1: print('AUTH FAILED'); sys.exit(1)
for cmd in sys.argv[1:]:
    s.sendall(pkt(2,2,cmd)); print('>',cmd,'->',recv(s)[1][:1500])
