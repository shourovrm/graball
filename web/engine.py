"""Run yt-dlp with public-address-only Python network connections.

Deploy behind an egress firewall too: native tools are separate processes.
"""
import ipaddress
import socket

_original_getaddrinfo = socket.getaddrinfo
_original_connect = socket.socket.connect
_original_connect_ex = socket.socket.connect_ex

def public_addresses(*args, **kwargs):
    answers = _original_getaddrinfo(*args, **kwargs)
    if any(not ipaddress.ip_address(item[4][0]).is_global for item in answers):
        raise OSError('Private and reserved network destinations are blocked')
    return answers

def public_connect(sock, address):
    if sock.family in (socket.AF_INET, socket.AF_INET6):
        public_addresses(address[0], address[1], sock.family, socket.SOCK_STREAM)
    return _original_connect(sock, address)

def public_connect_ex(sock, address):
    if sock.family in (socket.AF_INET, socket.AF_INET6):
        public_addresses(address[0], address[1], sock.family, socket.SOCK_STREAM)
    return _original_connect_ex(sock, address)

socket.getaddrinfo = public_addresses
socket.socket.connect = public_connect
socket.socket.connect_ex = public_connect_ex

if __name__ == '__main__':
    import yt_dlp
    yt_dlp.main()
