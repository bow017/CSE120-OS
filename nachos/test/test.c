#include "stdio.h"
#include "stdlib.h"

int main() {
  int fd = open ("write.out");
  if (fd >= 0) {
  printf ("...passed (fd = %d)\n", fd);
    } 
  else {
  printf ("...failed (%d)\n", fd);
  exit (-1002);
  }
  char buffer[128];
  int r = read (fd, buffer, 10);
  if (r != 10) {
  printf ("...failed (r = %d)\n", r);
  }
  r = 0;
  while(r < 10) {
    printf("%c", buffer[r]);
    r++;
  }
  return 0;
}
