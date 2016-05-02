Anvendelse
==========

```
indlever -h
```

Indlevere en folder med blanketter
```
indlever folder
```

Prøv
```
mkdir -p /tmp/sample_files/2017
echo "Invalid" > /tmp/sample_files/2017/100-1.xml
./indlever.groovy -n -d Udlån -c 19552101 -p 2017 /tmp/sample_files/2017
```